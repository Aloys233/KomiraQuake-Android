package com.aloys23.komiraquake.service

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 告警音效播放。资源位于 assets/sounds/{srev,general}（与旧版一致）。
 * 强制使用 USAGE_ALARM 警报通道并请求音频焦点，确保即便媒体静音也能发声并压低其他背景音。
 */
class AlertSoundService(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: Any? = null

    private val players = HashMap<String, MediaPlayer>()
    private val lastPlayed = HashMap<String, Long>()
    private val available = HashSet<String>()

    var enabled: Boolean = true
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            players.values.forEach { runCatching { it.setVolume(field, field) } }
        }

    init {
        available += listAssetDir("sounds/srev")
        available += listAssetDir("sounds/general")
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }

    @Synchronized
    fun play(key: String, cooldownMs: Long = 0) {
        if (!enabled || volume <= 0f) return
        val path = assetPathFor(key) ?: return
        if (key !in available && path.substringAfterLast('/') !in available) return

        val now = SystemClock.elapsedRealtime()
        if (cooldownMs > 0) {
            val last = lastPlayed[key]
            if (last != null && now - last < cooldownMs) return
        }
        lastPlayed[key] = now

        requestFocus()
        runCatching {
            val player = players.getOrPut(path) { createPlayer(path) }
            player.seekTo(0)
            player.setVolume(volume, volume)
            player.start()
        }.onFailure {
            players.remove(path)?.let { player -> runCatching { player.release() } }
            abandonFocus()
        }
    }

    /** 倒计时片段：优先播放预录 `{n}s.mp3`，无对应片段则静默跳过。 */
    fun playCountdownClip(secondsLeft: Int) {
        if (secondsLeft < 0 || secondsLeft > 60) return
        val file = "${secondsLeft}s.mp3"
        if (file in available) play("${secondsLeft}s") else play("countdown")
    }

    fun playIntense() = play("intense")

    @Synchronized
    fun stopAll() {
        // A stopped MediaPlayer cannot start again without prepare(). Recreate on next play.
        players.values.forEach { runCatching { it.release() } }
        players.clear()
        lastPlayed.clear()
        abandonFocus()
    }

    @Synchronized
    fun release() = stopAll()

    private fun createPlayer(path: String): MediaPlayer {
        val afd = context.assets.openFd(path)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return MediaPlayer().apply {
            setAudioAttributes(audioAttrs)
            afd.use {
                setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            setVolume(volume, volume)
            prepare()
        }
    }

    private fun assetPathFor(key: String): String? = when {
        key == "countdown" -> "sounds/general/countdown.wav"
        key == "intense" -> "sounds/general/intense.wav"
        key == "ews" -> "sounds/general/ews.mp3"
        key.endsWith("s") && key.dropLast(1).toIntOrNull() != null -> "sounds/general/$key.mp3"
        else -> "sounds/srev/$key.mp3"
    }

    private fun listAssetDir(dir: String): List<String> =
        runCatching { context.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
}

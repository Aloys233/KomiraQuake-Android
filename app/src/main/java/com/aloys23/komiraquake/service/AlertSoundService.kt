package com.aloys23.komiraquake.service

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

import com.aloys23.komiraquake.core.SoundQueue

/**
 * 告警音效播放。资源位于 assets/sounds/{srev,general}（与旧版一致）。
 * 强制使用 USAGE_ALARM 警报通道并请求音频焦点，确保即便媒体静音也能发声并压低其他背景音。
 */
class AlertSoundService(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: Any? = null

    private val queue = SoundQueue()
    private var current: MediaPlayer? = null
    private val cueQueue = ArrayDeque<String>()

    /** 提示通道播放器：倒计时与抵达提示走这里，与语句通道并发。 */
    private var cue: MediaPlayer? = null
    private val lastPlayed = HashMap<String, Long>()
    private val available = HashSet<String>()

    var enabled: Boolean = true
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            runCatching { current?.setVolume(field, field) }
            runCatching { cue?.setVolume(field, field) }
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
        queue.enqueue(path)
        pumpQueue()
    }

    /** 队列串行播放：仅在无片段播放中时启动下一段，避免新播报打断未播完的语句。 */
    private fun pumpQueue() {
        if (current != null) return
        val path = queue.take() ?: return
        val player = runCatching { createPlayer(path) }.getOrNull()
        if (player == null) {
            abandonFocus()
            return
        }
        current = player
        player.setOnCompletionListener { onClipFinished() }
        runCatching { player.start() }.onFailure {
            current = null
            runCatching { player.release() }
            abandonFocus()
        }
    }

    private fun onClipFinished() {
        val finished = current
        current = null
        runCatching { finished?.release() }
        if (queue.isEmpty()) {
            abandonFocus()
            return
        }
        pumpQueue()
    }

    /** 倒计时片段：优先播放预录 `{n}s.mp3`，无对应片段则回退到通用 countdown。 */
    fun playCountdownClip(secondsLeft: Int) {
        if (!enabled || volume <= 0f) return
        if (secondsLeft < 0 || secondsLeft > 60) return
        // 20/30/40/50/60s 是 1.7~1.8s 的整句播报，比倒计时周期长。
        // 正在播这类句子时必须让它说完，否则「还有 N秒抵达」每次都被切断。
        if (cue != null) return
        val file = "${secondsLeft}s.mp3"
        val path = if (file in available) assetPathFor("${secondsLeft}s") else assetPathFor("countdown")
        if (path == null || file !in available && path.substringAfterLast('/') !in available) return
        requestFocus()
        // 提示通道独立于语句通道：长音频（intense 3.1s）不会再堵死每秒一次的秒数。
        runCatching {
            cue = createPlayer(path).apply {
                setOnCompletionListener { releaseCue() }
                start()
            }
        }.onFailure {
            releaseCue()
        }
    }

    private fun releaseCue() {
        val finished = cue
        cue = null
        runCatching { finished?.release() }
        if (cueQueue.isEmpty()) {
            if (queue.isEmpty()) abandonFocus()
            return
        }
        pumpCueQueue()
    }

    /** 抵达提示序列：`0s` + 两下计时音，连着播完，不被后续秒数打断。 */
    fun playArrivalCues() {
        if (!enabled || volume <= 0f) return
        for (key in listOf("0s", "countdown", "countdown")) {
            val path = assetPathFor(key) ?: continue
            if (path.substringAfterLast('/') in available) cueQueue.addLast(path)
        }
        requestFocus()
        pumpCueQueue()
    }

    /** 启动提示通道待播序列的下一段。 */
    private fun pumpCueQueue() {
        if (cue != null) return
        val path = cueQueue.removeFirstOrNull() ?: return
        runCatching {
            cue = createPlayer(path).apply {
                setOnCompletionListener { releaseCue() }
                start()
            }
        }.onFailure {
            releaseCue()
        }
    }

    fun playIntense() = play("intense")

    @Synchronized
    fun stopAll() {
        queue.clear()
        cueQueue.clear()
        // A stopped MediaPlayer cannot start again without prepare(). Recreate on next play.
        runCatching { current?.release() }
        current = null
        runCatching { cue?.release() }
        cue = null
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

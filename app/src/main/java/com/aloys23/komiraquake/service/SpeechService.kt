package com.aloys23.komiraquake.service

import android.content.Context
import android.os.SystemClock
import android.os.Bundle
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 语音播报（zh-CN）。初始化失败时静默降级。 */
class SpeechService(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var available = false
    private var initialized = false
    private val lastSpoken = HashMap<String, Long>()

    var enabled: Boolean = false
    var rate: Float = 0.5f
    var volume: Float = 1.0f

    fun init(onReady: (() -> Unit)? = null) {
        if (initialized) {
            onReady?.invoke()
            return
        }
        initialized = true
        tts = TextToSpeech(context) { status ->
            available = status == TextToSpeech.SUCCESS
            if (available) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setSpeechRate(rate)
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
            onReady?.invoke()
        }
    }

    fun speak(text: String, dedupeKey: String? = null, dedupeWindowMs: Long = 4000) {
        if (!enabled || !available || volume <= 0f || text.isBlank()) return
        if (dedupeKey != null) {
            val now = SystemClock.elapsedRealtime()
            val last = lastSpoken[dedupeKey]
            if (last != null && now - last < dedupeWindowMs) return
            if (lastSpoken.size >= 256) lastSpoken.clear()
            lastSpoken[dedupeKey] = now
        }
        runCatching {
            tts?.setSpeechRate(rate)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, dedupeKey ?: "speech")
        }
    }

    fun speakSample() {
        speak("地震预警测试：横波预计三十秒后到达，请就近避难。", dedupeKey = "sample")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        available = false
        initialized = false
        lastSpoken.clear()
    }
}

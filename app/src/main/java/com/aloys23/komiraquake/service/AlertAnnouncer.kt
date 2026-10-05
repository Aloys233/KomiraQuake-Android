package com.aloys23.komiraquake.service

import com.aloys23.komiraquake.data.prefs.SettingsStore
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel

/** Event-scoped announcements; report corrections never reset arrival/countdown markers. */
class AlertAnnouncer(
    private val settings: SettingsStore,
    private val sound: AlertSoundService,
    private val speech: SpeechService,
) {
    internal class EventState {
        var report = 0
        var issued = false
        var final = false
        var warned = false
        var cautioned = false
        var intense = false
        var arrived = false
        var muted = false
        var stopped = false
        val countdowns = HashSet<Int>()

        data class Phase(val first: Boolean, val newFinal: Boolean)
        fun acceptReport(reportNum: Int, isFinal: Boolean): Phase? {
            val phase = Phase(!issued, isFinal && !final)
            // Same-report final corrections update state, but must never announce again.
            final = final || isFinal
            if (!phase.first && reportNum <= report) return null
            report = maxOf(report, reportNum)
            issued = true
            return phase
        }
    }

    private val states = LinkedHashMap<String, EventState>()
    private var outputOwner: String? = null

    @Synchronized
    fun onWarning(event: EarthquakeEvent) {
        if (event.isCanceled) {
            clear(event.identity)
            return
        }
        val state = stateFor(event)
        val policy = policy(event, state)
        if (!policy.eligible) return
        // Capture first before setting issued: first reports speak with updates disabled.
        val phase = state.acceptReport(event.reportNum, event.isFinal) ?: return
        val first = phase.first
        val newFinal = phase.newFinal
        if (!policy.audio && !policy.speech) return
        claimOutput(event.identity)
        if (policy.audio) {
            when {
                first -> sound.play("issue")
                newFinal -> sound.play("final")
                else -> sound.play("update", cooldownMs = 3000)
            }
            if (event.warningLevel == WarningLevel.CRITICAL && !state.warned) {
                state.warned = true
                state.cautioned = true
                sound.play("warn")
            } else if (event.warningLevel == WarningLevel.WARNING && !state.cautioned) {
                state.cautioned = true
                sound.play("caution")
            }
        }
        if (policy.speech) {
            val text = when {
                newFinal -> "${event.location} 地震，最终报，震级 ${fmt(event.magnitude)}。"
                first -> "${event.location} 发生地震，预估烈度 ${event.estimatedIntensity}，震级 ${fmt(event.magnitude)}。"
                event.warningLevel == WarningLevel.CRITICAL -> "严重地震预警，${event.location}，请立即避险。"
                settings.current.speakUpdates -> "地震预警更新，${event.location}，震级 ${fmt(event.magnitude)}。"
                else -> ""
            }
            speech.speak(text, dedupeKey = "${event.identity}:${event.reportNum}:${event.isFinal}")
        }
    }

    @Synchronized
    fun onCountdown(event: EarthquakeEvent) {
        if (event.sWaveArrival == null || !event.warningLevel.isAlert) return
        val state = stateFor(event)
        val policy = policy(event, state)
        if ((!policy.audio && !policy.speech) || state.arrived) return
        val seconds = event.remainingSeconds()
        if (seconds !in 1..60 || !state.countdowns.add(seconds)) return
        claimOutput(event.identity)
        if (policy.audio) {
            sound.playCountdownClip(seconds)
            if (seconds <= 10 && !state.intense) {
                state.intense = true
                sound.playIntense()
            }
        }
        if (policy.speech && settings.current.speakCountdown && seconds in COUNTDOWN_SPEECH_SECONDS) {
            speech.speak("预计还有 $seconds 秒。", dedupeKey = "${event.identity}:countdown:$seconds")
        }
    }

    @Synchronized
    fun onArrived(event: EarthquakeEvent) {
        if (event.sWaveArrival == null || !event.warningLevel.isAlert) return
        val state = stateFor(event)
        if (state.arrived) return
        state.arrived = true
        if (policy(event, state).audio) {
            claimOutput(event.identity)
            sound.play("hypocenter", cooldownMs = 10_000)
        }
    }

    @Synchronized
    fun onMuteChanged() {
        if (settings.current.isMuted) stop()
        else {
            if (!settings.current.enableSoundAlert || settings.current.alertVolume <= 0) sound.stopAll()
            if (!settings.current.enableSpeech || settings.current.alertVolume <= 0) speech.stop()
        }
    }

    /** Stop outputs without discarding one-shot markers. */
    @Synchronized
    fun stop() {
        sound.stopAll()
        speech.stop()
        outputOwner = null
    }

    @Synchronized
    fun muteEvent(eventId: String) {
        states.getOrPut(eventId) { EventState() }.muted = true
        if (outputOwner == eventId) stop()
    }

    @Synchronized
    fun stopEvent(eventId: String) {
        states.getOrPut(eventId) { EventState() }.stopped = true
        if (outputOwner == eventId) stop()
    }

    /** Removing A must not silence B. Repository terminal tombstones reject future A updates. */
    @Synchronized
    fun clear(eventId: String? = null) {
        if (eventId == null) {
            states.clear()
            stop()
        } else {
            states.remove(eventId)
            if (outputOwner == eventId) stop()
        }
    }

    private fun claimOutput(identity: String) {
        if (outputOwner != null && outputOwner != identity) stop()
        outputOwner = identity
    }

    private fun policy(event: EarthquakeEvent, state: EventState) =
        AlertPolicy.evaluate(event, settings.current, state.muted, state.stopped)

    private fun stateFor(event: EarthquakeEvent): EventState = states.getOrPut(event.identity) { EventState() }
    private fun fmt(magnitude: Double) = "%.1f".format(magnitude)

    companion object {
        private val COUNTDOWN_SPEECH_SECONDS = setOf(10, 20, 30)
    }
}

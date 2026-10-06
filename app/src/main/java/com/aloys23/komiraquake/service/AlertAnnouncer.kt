package com.aloys23.komiraquake.service

import com.aloys23.komiraquake.data.prefs.SettingsStore
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel

/** Event-scoped announcements; report corrections never reset arrival/countdown markers. */
class AlertAnnouncer(
    private val settings: SettingsStore,
    private val sound: AlertSoundService,
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
        val phase = state.acceptReport(event.reportNum, event.isFinal) ?: return
        if (!policy.audio) return
        claimOutput(event.identity)
        when {
            phase.first -> sound.play("issue")
            phase.newFinal -> sound.play("final")
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

    @Synchronized
    fun onCountdown(event: EarthquakeEvent) {
        if (event.sWaveArrival == null || !event.warningLevel.isAlert) return
        val state = stateFor(event)
        val policy = policy(event, state)
        if (!policy.audio || state.arrived) return
        val seconds = event.remainingSeconds()
        if (seconds !in 1..60 || !state.countdowns.add(seconds)) return
        claimOutput(event.identity)
        sound.playCountdownClip(seconds)
        if (seconds <= 10 && !state.intense) {
            state.intense = true
            sound.playIntense()
        }
    }

    @Synchronized
    fun onArrived(event: EarthquakeEvent) {
        if (event.sWaveArrival == null) return
        val state = stateFor(event)
        if (state.arrived) return
        // 不能用当前 warningLevel 判定：晚到的报次可能把等级降级（如 directory 重算为 WATCH），
        // 那样已经播过的倒计时会在到时凭空断掉、没有抵达播报。
        // 以「本事件确实播过倒计时」为准，保证倒计时与抵达播报成对出现。
        if (state.countdowns.isEmpty() && !event.warningLevel.isAlert) return
        state.arrived = true
        if (policy(event, state).audio) {
            claimOutput(event.identity)
            // 抵达提示：`0s` + 两下计时音走提示通道连播，与 hypocenter 并发。
            sound.playArrivalCues()
            sound.play("hypocenter", cooldownMs = 10_000)
        }
    }

    @Synchronized
    fun onMuteChanged() {
        if (settings.current.isMuted) stop()
        else if (!settings.current.enableSoundAlert || settings.current.alertVolume <= 0) sound.stopAll()
    }

    /** Stop outputs without discarding one-shot markers. */
    @Synchronized
    fun stop() {
        sound.stopAll()
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
}

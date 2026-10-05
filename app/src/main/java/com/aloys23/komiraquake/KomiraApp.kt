package com.aloys23.komiraquake

import android.app.Application
import android.content.Context
import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.CityCoordTable
import com.aloys23.komiraquake.core.ClockInfo
import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.NtpTimeService
import com.aloys23.komiraquake.core.QuakeCalculator
import com.aloys23.komiraquake.core.TravelTimeService
import com.aloys23.komiraquake.data.QuakeRepository
import com.aloys23.komiraquake.data.db.HistoryStore
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.data.prefs.SettingsStore
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.service.AlertAnnouncer
import com.aloys23.komiraquake.service.AlertSoundService
import com.aloys23.komiraquake.service.DndController
import com.aloys23.komiraquake.service.LocationService
import com.aloys23.komiraquake.service.SpeechService
import com.aloys23.komiraquake.service.WarningService
import com.aloys23.komiraquake.ui.map.TileCacheInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class KomiraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** 应用级依赖容器：数据源、存储、定位、告警编排与倒计时。 */
class AppContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 瓦片专用客户端：共享连接池，另挂 64 MB 磁盘缓存，重复区域/重启后不再重新下载。 */
    val tileClient: OkHttpClient = client.newBuilder()
        .cache(Cache(File(context.cacheDir, "tiles"), 64L * 1024 * 1024))
        .addNetworkInterceptor(TileCacheInterceptor)
        .build()

    val settings = SettingsStore(context)
    val location = LocationService(context, client, scope)
    val history = HistoryStore(context)

    /** 网络授时：SNTP 为主、HTTP 为备，结果写入 AppClock。《NATIVE_PORT_SPEC》 §13。 */
    val ntp = NtpTimeService(scope, client)

    val sound = AlertSoundService(context)
    val speech = SpeechService(context)
    val vibrator = com.aloys23.komiraquake.service.VibratorController(context)
    val announcer = AlertAnnouncer(settings, sound, speech)

    /** 勿扰绕过：预警前临时解除 DND，结束后恢复。《NATIVE_PORT_SPEC》 §15。 */
    val dnd = DndController(context, settings, scope)

    private val mutedEvents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 自定义 NTP 服务器上次应用的取值；用于变更时触发一次即时重校。 */
    private var lastCustomNtp: String? = null
    private var ntpStarted = false

    val repository = QuakeRepository(
        scope, settings, location, history, client,
        onAlert = { event ->
            val policy = com.aloys23.komiraquake.service.AlertPolicy.evaluate(event, settings.current, event.identity in mutedEvents)
            if (policy.dnd) dnd.engage()
            announcer.onWarning(event)
        },
    )

    /** 1Hz 倒计时（秒），供 UI 与全屏预警使用。 */
    val countdown = MutableStateFlow(0)

    /** 校时状态，供设置页展示。 */
    val clockInfo: StateFlow<ClockInfo> = AppClock.info

    init {
        runCatching {
            context.assets.open(TravelTimeService.ASSET_PATH).bufferedReader().use {
                TravelTimeService.loadFromString(it.readText())
            }
        }
        runCatching {
            context.assets.open(CityCoordTable.ASSET_PATH).bufferedReader().use {
                CityCoordTable.loadFromString(it.readText())
            }
        }
        speech.init()
        applySettings(settings.current)
        scope.launch { settings.state.collect { applySettings(it) } }
        scope.launch { observeWarnings() }
    }

    fun start() {
        repository.start()
        // 定位已在 LocationService 构造时恢复；仅有记录才跳过自动 IP，避免覆盖基准地。
        if (!location.state.value.hasLocation) location.requestCurrentPosition()
        ntp.start()
        ntpStarted = true
    }

    private fun applySettings(s: Settings) {
        sound.enabled = s.enableSoundAlert
        sound.volume = s.alertVolume.toFloat()
        speech.enabled = s.enableSpeech
        speech.rate = s.speechRate.toFloat()
        speech.volume = s.alertVolume.toFloat()
        AppClock.enable(s.enableNtpSync)
        // 自定义 NTP 服务器变更：立即按新顺序重新校准一次。
        val customNtp = s.customNtpServer.trim()
        if (customNtp != lastCustomNtp) {
            lastCustomNtp = customNtp
            ntp.setCustomServer(customNtp)
            if (ntpStarted) ntp.refresh()
        }
        announcer.onMuteChanged()
    }

    private suspend fun observeWarnings() {
        var previous = emptySet<String>()
        var presented: EarthquakeEvent? = null
        var presentationAccess: Pair<Boolean, Boolean>? = null
        var vibrating: String? = null
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val events = repository.activeWarnings.value
            val identities = events.map { it.identity }.toSet()
            (previous - identities).forEach { announcer.clear(it); mutedEvents.remove(it) }
            previous = identities
            val selected = repository.activeWarning.value
            // 只有达到提醒门槛（总开关 + 本地烈度过滤）的事件才展示倒计时。
            val selectedEligible = selected?.let {
                com.aloys23.komiraquake.service.AlertPolicy.evaluate(
                    it, settings.current, it.identity in mutedEvents).eligible
            } == true
            countdown.value = if (selectedEligible && selected != null && selected.sWaveArrival != null)
                selected.remainingSeconds() else -1
            val decisions = events.map { it to com.aloys23.komiraquake.service.AlertPolicy.evaluate(
                it, settings.current, it.identity in mutedEvents) }
            if (decisions.any { it.second.dnd }) dnd.engage() else dnd.release()
            val vibration = decisions.lastOrNull { it.second.vibration }?.first
            if (vibration?.identity != vibrating) {
                vibrator.stop()
                if (vibration != null) vibrator.startAlert(vibration.warningLevel)
                vibrating = vibration?.identity
            }
            val show = selected?.takeIf { repository.warningOverlayVisible.value &&
                com.aloys23.komiraquake.service.AlertPolicy.evaluate(it, settings.current, it.identity in mutedEvents).fullScreen }
            val access = com.aloys23.komiraquake.service.SystemPermissions.notificationsEnabled(context) to
                com.aloys23.komiraquake.service.SystemPermissions.canUseFullScreenIntent(context)
            if (show != presented || access != presentationAccess) {
                if (show?.identity != presented?.identity || !access.first) WarningService.stop(context)
                if (show != null && access.first) WarningService.start(context, show)
                presented = show
                presentationAccess = access
            }
            events.forEach { event ->
                announcer.onWarning(event)
                if (event.sWaveArrival != null) {
                    if (event.remainingSeconds() <= 0) announcer.onArrived(event)
                    else announcer.onCountdown(event)
                }
            }
            if (events.isEmpty()) announcer.stop()
            delay(250)
        }
    }

    fun clearHistory() = repository.clearHistory()

    /** Collapse only the visual full-screen surface; alert output and HUD continue. */
    fun collapseAlert(identity: String? = null) {
        repository.dismissWarningOverlay(identity)
        if (identity == null || repository.activeWarning.value?.identity == identity) WarningService.stop(context)
    }
    fun dismissAlert(identity: String? = null) = collapseAlert(identity)

    /** Silence only this event, leaving its visual countdown active. */
    fun muteAlert(identity: String? = null) {
        val key = identity ?: repository.activeWarning.value?.identity ?: return
        mutedEvents.add(key)
        announcer.muteEvent(key)
        if (repository.activeWarnings.value.none { it.identity != key && it.identity !in mutedEvents }) {
            vibrator.stop(); dnd.release()
        }
    }

    /** Stop is terminal for this event; ordinary updates cannot restart it. */
    fun stopAlert(identity: String? = null) {
        val key = identity ?: repository.activeWarning.value?.identity ?: return
        announcer.stopEvent(key)
        repository.clearActiveWarning(key)
        mutedEvents.remove(key)
        if (repository.activeWarnings.value.isEmpty()) {
            vibrator.stop(); dnd.release(); WarningService.stop(context)
        }
    }
}

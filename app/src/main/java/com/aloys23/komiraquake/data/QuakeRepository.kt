package com.aloys23.komiraquake.data

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.NetworkGate
import com.aloys23.komiraquake.core.QuakeCalculator
import com.aloys23.komiraquake.data.db.HistoryStore
import com.aloys23.komiraquake.data.gate.EventGate
import com.aloys23.komiraquake.data.gate.EventGateDecision
import com.aloys23.komiraquake.data.gate.EventLifecycle
import com.aloys23.komiraquake.data.prefs.SettingsStore
import com.aloys23.komiraquake.data.source.EarthquakeSource
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.data.source.jian.JianSource
import com.aloys23.komiraquake.data.source.pancakes.PancakesSource
import com.aloys23.komiraquake.data.source.simulated.SimulatedSource
import com.aloys23.komiraquake.data.source.wolfx.WolfxSource
import com.aloys23.komiraquake.data.source.whews.WhewsSource
import com.aloys23.komiraquake.model.ConnectionStatus
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.service.AlertPolicy
import com.aloys23.komiraquake.service.LocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Directory ingestion and live warning lifecycle are intentionally separate. */
class QuakeRepository(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val location: LocationService,
    private val history: HistoryStore,
    private val client: OkHttpClient,
    private val onAlert: (EarthquakeEvent) -> Unit = {},
    private val now: () -> Long = { AppClock.now() },
    /** 网络感知重连；默认恒在线，退化为各源固定退避。 */
    private val networkGate: NetworkGate = NetworkGate.AlwaysOnline,
) {
    private val directoryGate = EventGate()
    // Restore terminal identities before any source or collector can admit a replay.
    private val lifecycle = EventLifecycle(now, restoredTerminals = history.loadTerminals(now()),
        persistTerminal = history::saveTerminal)
    private var started = false
    private val collapsed = HashSet<String>()
    private fun userPosition(): Pair<Double, Double>? = location.state.value.let {
        val lat = it.latitude; val lon = it.longitude
        if (lat != null && lon != null) lat to lon else null
    }
    // 数据源注册表：Wolfx / Pancakes / Jian / Whews … 平级、互为备份。新增源在此登记一行即可，
    // 接线、启停、状态聚合与跨源合并都由通用逻辑处理。
    private val jian = JianSource(
        scope, client, ::userPosition, { settings.current.intensityStandard },
        onRefreshToken = { rt -> settings.update { it.copy(jianRefreshToken = rt) } },
        networkGate = networkGate,
    )
    private val whews = WhewsSource(
        scope, client, ::userPosition, { settings.current.intensityStandard },
        networkGate = networkGate,
    )
    /** 模拟源仅开发自测：需开发者模式开启且地址非空，否则 isConfigured() 为假、不会连接。 */
    private val simulated = SimulatedSource(
        scope, client, ::userPosition, { settings.current.intensityStandard },
        devModeProvider = { settings.current.developerMode },
        networkGate = networkGate,
    )
    private val sources: List<EarthquakeSource> = listOf(
        WolfxSource(scope, client, ::userPosition, { settings.current.intensityStandard },
            networkGate = networkGate),
        PancakesSource(scope, client, ::userPosition, { settings.current.intensityStandard },
            networkGate = networkGate),
        jian,
        whews,
        simulated,
    )
    private val _history = MutableStateFlow<List<EarthquakeEvent>>(emptyList())
    val historyEvents = _history.asStateFlow()
    private val _eventList = MutableStateFlow<List<EarthquakeEvent>>(emptyList())

    /** 列表数据：目录条目用权威数据，并与实时预警按「发震时刻 + 震中」合并为一条。 */
    val eventList = _eventList.asStateFlow()
    private val _activeWarnings = MutableStateFlow<List<EarthquakeEvent>>(emptyList())
    val activeWarnings = _activeWarnings.asStateFlow()
    private val _activeWarning = MutableStateFlow<EarthquakeEvent?>(null)
    val activeWarning = _activeWarning.asStateFlow()
    /** HUD 在全部活动事件中的当前位置（0 基）与总数，供左右切换展示 `n/N`。 */
    private val _hudIndex = MutableStateFlow(0)
    val hudIndex = _hudIndex.asStateFlow()
    private val _hudCount = MutableStateFlow(0)
    val hudCount = _hudCount.asStateFlow()
    /** 当前选中的活动事件 identity；HUD 左右切换与新增事件都更新它。 */
    private var selectedWarning: String? = null
    private val _mapFocus = MutableStateFlow<EarthquakeEvent?>(null)
    val mapFocus = _mapFocus.asStateFlow()
    private val _mapCameraRequest = MutableStateFlow(0L)
    val mapCameraRequest = _mapCameraRequest.asStateFlow()
    /** 各数据源的独立链路状态，设置页逐条展示。 */
    val sourceInfos: StateFlow<List<DataSourceInfo>> =
        combine(sources.map { it.status }) { arr -> arr.toList() }
            .stateIn(scope, SharingStarted.Eagerly, sources.map { it.status.value })
    /** 状态栏用的聚合状态：任一启用源在线即视为在线。 */
    val sourceInfo: StateFlow<DataSourceInfo> =
        combine(sourceInfos, settings.state) { infos, s -> aggregateSources(infos.filter { s.enabled(it.id) }) }
            .stateIn(scope, SharingStarted.Eagerly, aggregateSources(sources.map { it.status.value }))
    private val _warningOverlayVisible = MutableStateFlow(false)
    val warningOverlayVisible = _warningOverlayVisible.asStateFlow()

    init {
        jian.setRefreshToken(settings.current.jianRefreshToken)
        whews.setToken(settings.current.whewsToken)
        simulated.setUrl(settings.current.simulatedUrl)
        sources.forEach { src ->
            scope.launch { src.events.collect { ev ->
                synchronized(this@QuakeRepository) {
                    if (started && settings.current.enabled(src.id) && src.isCurrent(ev))
                        handleEvent(ev.event, ev.kind == SourceEventKind.DIRECTORY)
                }
            } }
        }
        scope.launch { location.state.collect { recalculate() } }
        scope.launch { settings.state.collect {
            synchronized(this@QuakeRepository) {
                applySourceToggles(it)
                recalculate()
            }
        } }
        scope.launch { while (isActive) {
            delay(1000)
            synchronized(this@QuakeRepository) { lifecycle.expire(); publish() }
        } }
    }

    /** 依据启用集合启停各数据源；未启动或凭据未就绪时一律停，避免无凭据连接与后台空跑。 */
    private fun applySourceToggles(s: com.aloys23.komiraquake.data.prefs.Settings) {
        // 令牌/地址变化须先于启停生效，否则新令牌要等下次启动才生效。
        whews.setToken(s.whewsToken)
        simulated.setUrl(s.simulatedUrl)
        for (src in sources) {
            if (started && s.enabled(src.id) && src.isConfigured()) src.start() else src.stop()
        }
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        _history.value = history.loadRecent(HistoryStore.DEFAULT_LIMIT)
        recalculate()
        applySourceToggles(settings.current)
    }
    @Synchronized fun stop() { started = false; for (src in sources) src.stop() }
    fun reloadCredentials() = Unit
    @Synchronized fun dismissWarningOverlay(identity: String? = null) {
        (identity ?: _activeWarning.value?.identity)?.let(collapsed::add)
        publish()
    }
    @Synchronized fun clearActiveWarning(identity: String? = null) {
        (identity ?: _activeWarning.value?.identity)?.let { lifecycle.stop(it); collapsed.remove(it) }
        publish()
    }
    fun isMapFocused(id: String): Boolean = _mapFocus.value?.let { it.identity == id || it.id == id } == true
    @Synchronized fun toggleMapFocus(id: String) {
        if (isMapFocused(id)) { _mapFocus.value = null; return }
        findEvent(id)?.let { _mapFocus.value = it; _mapCameraRequest.value++ }
    }    @Synchronized fun locateEpicenter(eventId: String? = null) {
        val event = eventId?.let(::findEvent) ?: _mapFocus.value ?: _activeWarning.value ?: _history.value.firstOrNull()
        if (event != null) { _mapFocus.value = event; _mapCameraRequest.value++ }
    }
    private fun findEvent(id: String) = (lifecycle.events + _history.value).firstOrNull { it.identity == id || it.id == id }

    private fun handleEvent(raw: EarthquakeEvent, directory: Boolean) {
        val event = EewParser.recalculate(raw, userPosition(), settings.current.intensityStandard, directory)
        if (directory) {
            when (directoryGate.admit(event, now())) {
                EventGateDecision.DUPLICATE, EventGateDecision.STALE -> {
                    simulated.onAdmission(event, "duplicate"); return
                }
                else -> upsertHistory(event)
            }
            if (_mapFocus.value?.identity == event.identity && lifecycle.events.none { it.identity == event.identity }) _mapFocus.value = event
            refreshEventList()
            simulated.onAdmission(event, "applied")
            return
        }
        val newEvent = lifecycle.events.none { it.identity == event.identity }
        val decision = lifecycle.accept(event)
        if (decision == EventGateDecision.STALE || decision == EventGateDecision.DUPLICATE) {
            // 墓碑与门控去重都归为 STALE/DUPLICATE，但成因不同：前者是「这个事件已结束过」，
            // 后者是「这一报和已收的重复」。分开回报才能定位问题。
            publish()
            simulated.onAdmission(event, if (event.isCanceled) "ended" else "tombstoned")
            return
        }
        if (!event.isCanceled && newEvent) {
            selectedWarning = event.identity
            _activeWarning.value = event
            _mapFocus.value = event
            _mapCameraRequest.value++
        }
        publish()
        onAlert(event)
        simulated.onAdmission(event, "applied")
    }

    @Synchronized private fun recalculate() {
        val user = userPosition(); val standard = settings.current.intensityStandard
        lifecycle.recalculate { EewParser.recalculate(it, user, standard) }
        _history.value = _history.value.map { EewParser.recalculate(it, user, standard, true) }
        _mapFocus.value = _mapFocus.value?.let { focus ->
            lifecycle.events.firstOrNull { it.identity == focus.identity }
                ?: _history.value.firstOrNull { it.identity == focus.identity }
                ?: EewParser.recalculate(focus, user, standard)
        }
        publish()
    }
    private fun publish() {
        val events = lifecycle.events
        val old = _activeWarning.value
        _activeWarnings.value = events
        // 全部活动事件按发震时刻倒序（最新在前）；HUD 默认展示最新，左右切换改变选中项。
        val ordered = events.sortedByDescending { it.timestamp }
        val selected = ordered.firstOrNull { it.identity == selectedWarning } ?: ordered.firstOrNull()
        selectedWarning = selected?.identity
        _activeWarning.value = selected
        _hudCount.value = ordered.size
        _hudIndex.value = ordered.indexOfFirst { it.identity == selected?.identity }.coerceAtLeast(0)
        _mapFocus.value?.let { focus ->
            val updated = events.firstOrNull { it.identity == focus.identity }
            if (updated != null) _mapFocus.value = updated
            else if (old?.identity == focus.identity && events.none { it.identity == focus.identity }) _mapFocus.value = selected
        }
        _warningOverlayVisible.value = selected != null && selected.identity !in collapsed &&
            AlertPolicy.evaluate(selected, settings.current).fullScreen
        collapsed.retainAll(events.map { it.identity }.toSet())
        refreshEventList()
    }

    /** HUD 左右切换：在全部活动事件间循环（按发震时刻倒序，0 为最新）。 */
    fun previousWarning() = shiftWarning(-1)
    fun nextWarning() = shiftWarning(1)
    @Synchronized private fun shiftWarning(delta: Int) {
        val ordered = lifecycle.events.sortedByDescending { it.timestamp }
        if (ordered.isEmpty()) return
        var index = ordered.indexOfFirst { it.identity == selectedWarning }
        if (index < 0) index = 0
        index = ((index + delta) % ordered.size + ordered.size) % ordered.size
        selectedWarning = ordered[index].identity
        _mapFocus.value = ordered[index]
        _mapCameraRequest.value++
        publish()
    }
    private fun upsertHistory(event: EarthquakeEvent) {
        _history.value = (_history.value.filterNot { it.identity == event.identity } + event)
            .sortedByDescending { it.timestamp }.take(MAX_HISTORY)
        history.upsertAll(listOf(event)); history.prune(HistoryStore.DEFAULT_KEEP)
    }
    @Synchronized fun clearHistory() { history.clear(); _history.value = emptyList(); refreshEventList() }

    /**
     * 目录条目用权威数据；同一物理地震（发震时刻 + 震中）只保留一条，
     * 实时预警命中目录时用目录数据并标记 isActive，未命中目录的实时预警另行补上。
     */
    private fun refreshEventList() {
        val catalog = ArrayList<EarthquakeEvent>()
        for (e in _history.value) {
            val found = catalog.indexOfFirst { samePhysicalEvent(it, e) }
            if (found < 0) { catalog.add(e); continue }
            // 同一地震若有自动/正式两条，取推送时间较晚（通常为正式）的一条。
            val prev = catalog[found].sourceUpdatedAt ?: catalog[found].timestamp
            val next = e.sourceUpdatedAt ?: e.timestamp
            if (next > prev) catalog[found] = e
        }
        val emitted = ArrayList<EarthquakeEvent>()
        val out = ArrayList<EarthquakeEvent>()
        val used = BooleanArray(catalog.size)
        for (live in _activeWarnings.value) {
            if (emitted.any { samePhysicalEvent(it, live) }) continue
            val index = catalog.indices.firstOrNull { !used[it] && samePhysicalEvent(catalog[it], live) }
            val row = if (index != null) { used[index] = true; catalog[index].copy(isActive = true) } else live.copy(isActive = true)
            out.add(row); emitted.add(row)
        }
        catalog.forEachIndexed { i, e ->
            if (!used[i] && emitted.none { samePhysicalEvent(it, e) }) {
                out.add(e.copy(isActive = false)); emitted.add(e)
            }
        }
        _eventList.value = out.sortedByDescending { it.timestamp }
    }

    private fun samePhysicalEvent(a: EarthquakeEvent, b: EarthquakeEvent): Boolean =
        QuakeCalculator.isSameQuake(a.timestamp, a.latitude, a.longitude, b.timestamp, b.latitude, b.longitude)
    fun refreshCatalog() {
        if (!started) return
        for (src in sources) if (settings.current.enabled(src.id)) src.refreshDirectory()
    }

    /** Jian 登录：用登录密钥 `lk_…` 换取刷新令牌并持久化。 */
    fun loginJian(loginKey: String) = jian.login(loginKey)

    companion object {
        const val MAX_HISTORY = 200

        /** 聚合状态优先级：在线 > 连接中 > 异常 > 断开；用于单条状态栏展示。 */
        private fun aggregateSources(list: List<DataSourceInfo>): DataSourceInfo {
            if (list.isEmpty()) return DataSourceInfo(id = "none", name = "无启用数据源", region = "全球")
            val status = list.maxByOrNull { statusRank(it.status) }!!.status
            val directory = list.maxByOrNull { statusRank(it.directoryStatus) }!!.directoryStatus
            val connected = list.filter { it.status == ConnectionStatus.CONNECTED }
            return DataSourceInfo(
                id = list.joinToString("+") { it.id },
                name = list.joinToString(" · ") { it.name },
                region = "全球",
                status = status,
                latencyMs = connected.mapNotNull { it.latencyMs }.minOrNull(),
                lastHeartbeat = list.mapNotNull { it.lastHeartbeat }.maxOrNull(),
                description = list.joinToString("；") { it.description },
                directoryStatus = directory,
                directoryLatencyMs = list.mapNotNull { it.directoryLatencyMs }.minOrNull(),
                directoryLastSuccessAt = list.mapNotNull { it.directoryLastSuccessAt }.maxOrNull(),
                directoryError = list.firstNotNullOfOrNull { it.directoryError },
            )
        }

        private fun statusRank(s: ConnectionStatus): Int = when (s) {
            ConnectionStatus.CONNECTED -> 3
            ConnectionStatus.CONNECTING -> 2
            ConnectionStatus.ERROR -> 1
            ConnectionStatus.DISCONNECTED -> 0
        }
    }
}

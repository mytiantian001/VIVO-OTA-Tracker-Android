package io.github.long36708.updater.vivo

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.long36708.updater.AndroidAppContext
import io.github.long36708.updater.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VivoOtaViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(VivoOtaUiState())
    val uiState: StateFlow<VivoOtaUiState> = _uiState.asStateFlow()

    private val client: VivoOtaClient
    private val prefs: android.content.SharedPreferences

    init {
        val ctx = AndroidAppContext.getApplicationContext()
            ?: throw IllegalStateException("AndroidAppContext not initialized")
        prefs = ctx.getSharedPreferences("vivo_ota", Context.MODE_PRIVATE)
        VivoDeviceDatabase.load(ctx)
        client = VivoOtaClient(ctx)
        loadHistory()
        initCrypto()
        applyDefaultSelection()
        refreshDevices()
        loadDeviceImei()
    }

    /** 启动 / 手动刷新时读取本机 IMEI；读不到则退化为随机值，保证请求里的 imei 字段始终有效。 */
    fun loadDeviceImei() {
        viewModelScope.launch(Dispatchers.IO) {
            val fromDevice = try {
                VivoImei.readDevice(ctxApplication())
            } catch (e: Exception) {
                Log.w("VivoOtaViewModel", "Failed to read device IMEI", e)
                ""
            }
            _uiState.update {
                it.copy(
                    imei = fromDevice.ifEmpty { VivoImei.random() },
                    imeiSource = if (fromDevice.isNotEmpty()) ImeiSource.DEVICE else ImeiSource.RANDOM
                )
            }
        }
    }

    /** 用户手动填写：去掉非数字字符并截断到 15 位。 */
    fun updateImei(v: String) {
        _uiState.update { it.copy(imei = VivoImei.sanitize(v), imeiSource = ImeiSource.MANUAL) }
        clearStaleQueryResult()
    }

    /** 一键随机生成 15 位 IMEI。 */
    fun generateRandomImei() {
        _uiState.update { it.copy(imei = VivoImei.random(), imeiSource = ImeiSource.RANDOM) }
        clearStaleQueryResult()
    }

    /**
     * 后台从远端拉取最新设备列表，成功后修正当前选中项并提示。
     */
    private fun refreshDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            when (VivoDeviceDatabase.refresh(ctxApplication())) {
                VivoDeviceDatabase.RefreshResult.UPDATED -> {
                    _uiState.update { st ->
                        st.copy(toastMessage = ctxApplication().getString(R.string.devices_updated))
                    }
                    fixupSelectionAfterRefresh()
                }
                // 远端数据与缓存一致时同样要修正：冷启动读到的缓存系列名可能是远端命名
                // （"VIVO X 系列"），与内置默认名（"X 系列"）不同，applyDefaultSelection
                // 会落空导致 codename 为空、查询按钮永久禁用。
                VivoDeviceDatabase.RefreshResult.UNCHANGED -> {
                    if (needsSelectionFixup()) fixupSelectionAfterRefresh()
                }
                VivoDeviceDatabase.RefreshResult.FAILED -> Unit
            }
        }
    }

    /** 当前选择的系列在设备库中不存在，或 codename 仍为空 → 需要用现有数据重新定位。 */
    private fun needsSelectionFixup(): Boolean {
        val st = _uiState.value
        if (st.manualMode) return false
        val seriesList = VivoDeviceDatabase.series
        if (seriesList.isEmpty()) return false
        return st.selectedSeries !in seriesList || st.selectedCodename.isEmpty()
    }

    private fun ctxApplication(): Context =
        AndroidAppContext.getApplicationContext()
            ?: throw IllegalStateException("AndroidAppContext not initialized")

    /**
     * 设备列表更新后，尽量保持用户当前的选择；
     * 当前系列/机型不存在时回退到该系列的最近有效项。
     */
    private fun fixupSelectionAfterRefresh() {
        val st = _uiState.value
        val seriesList = VivoDeviceDatabase.series
        if (seriesList.isEmpty()) return
        val series = resolveSeries(seriesList, st.selectedSeries)
        val devices = VivoDeviceDatabase.devicesOf(series)
        if (devices.isEmpty()) return
        val byName = devices.indexOfFirst { it.model == st.selectedModel }
        val index = if (byName >= 0) byName else st.selectedModelIndex.coerceIn(0, devices.size - 1)
        val device = devices[index]
        _uiState.update {
            it.copy(
                selectedSeries = series,
                selectedModelIndex = index,
                selectedModel = device.model,
                selectedCodename = device.codename,
                selectedModelSwVer = device.model_sw_ver,
                deviceType = detectDeviceType(series)
            )
        }
        // ADR-003 D4：设备列表在线刷新后，若用户未手动覆盖版本号，跟随新选中机型的推荐值
        if (!_uiState.value.isSwVersionCustom) {
            applyRecommendedSwVersion()
        }
    }

    private fun applyDefaultSelection() {
        val defaultSeries = "X 系列"
        val defaultModel = "vivo X200 Pro mini"
        val seriesList = VivoDeviceDatabase.series
        if (seriesList.isEmpty()) return
        // 系列名在内置数据与远端数据里不同（"X 系列" vs "VIVO X 系列"），
        // 必须先解析成当前设备库里真实存在的名字，否则 devicesOf 返回空、
        // codename 为空导致查询按钮不可点击。
        val series = resolveSeries(seriesList, defaultSeries)
        val devices = VivoDeviceDatabase.devicesOf(series)
        val index = devices.indexOfFirst { it.model == defaultModel }.coerceAtLeast(0)
        val device = devices.getOrNull(index) ?: devices.firstOrNull() ?: return
        val detectedType = detectDeviceType(series)
        _uiState.update {
            it.copy(
                selectedSeries = series,
                selectedModelIndex = index,
                selectedModel = device.model,
                selectedCodename = device.codename,
                selectedModelSwVer = device.model_sw_ver,
                deviceType = detectedType,
                androidVersion = 15,
                isCustomAndroidVersion = false
            )
        }
        // ADR-003 D4：启动时尚未手动编辑过，若默认机型配置了推荐版本号则带入
        if (device.defaultSwVersion.isNotBlank()) {
            fillSwVersion(device.defaultSwVersion)
        }
    }

    private fun initCrypto() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                client.initCrypto()
            } catch (e: Exception) {
                Log.e("VivoOtaViewModel", "Crypto init failed", e)
                false
            }
            _uiState.update { it.copy(cryptoReady = ok) }
        }
    }

    fun selectSeries(series: String) {
        val devices = VivoDeviceDatabase.devicesOf(series)
        val first = devices.firstOrNull()
        val detectedType = detectDeviceType(series)
        _uiState.update {
            it.copy(
                selectedSeries = series,
                selectedModelIndex = 0,
                selectedModel = first?.model ?: "",
                selectedCodename = first?.codename ?: "",
                selectedModelSwVer = first?.model_sw_ver ?: "",
                deviceType = detectedType
            )
        }
        // ADR-003 D4：未手动编辑过版本号时，跟随新系列首个机型的推荐值
        val recommended = first?.defaultSwVersion.orEmpty()
        if (!_uiState.value.isSwVersionCustom && recommended.isNotBlank()) {
            fillSwVersion(recommended)
        }
        clearStaleQueryResult()
    }

    fun selectDevice(index: Int) {
        val devices = VivoDeviceDatabase.devicesOf(_uiState.value.selectedSeries)
        val device = devices.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                selectedModelIndex = index,
                selectedModel = device.model,
                selectedCodename = device.codename,
                selectedModelSwVer = device.model_sw_ver
            )
        }
        // ADR-003 D4：未手动编辑过版本号时才跟随机型推荐值
        if (!_uiState.value.isSwVersionCustom && device.defaultSwVersion.isNotBlank()) {
            fillSwVersion(device.defaultSwVersion)
        }
        clearStaleQueryResult()
    }

    /**
     * 只做「解析 + 写入」，不碰脏标记（ADR-003 D3）。
     *
     * 自动填充与用户手输都必须走这里：softwareVersion 点号前的数字决定 androidVersion，
     * 绕过本函数直接 copy(softwareVersion =) 会让 Android 16 机型填完后 androidVersion
     * 仍停在 15，查询直接查错。
     */
    private fun applySwVersion(v: String) {
        val majorVersion = if (v.contains('.')) {
            v.substringBefore('.').toIntOrNull()
        } else {
            v.toIntOrNull()?.takeIf { it in 13..17 }
        }
        if (majorVersion != null && majorVersion > 0) {
            if (majorVersion in 13..17) {
                _uiState.update { it.copy(softwareVersion = v, androidVersion = majorVersion, isCustomAndroidVersion = false) }
            } else {
                _uiState.update { it.copy(softwareVersion = v, androidVersion = majorVersion, isCustomAndroidVersion = true, customAndroidVersion = majorVersion.toString()) }
            }
        } else {
            _uiState.update { it.copy(softwareVersion = v) }
        }
    }

    /** 用户手动输入 → 置脏，之后切机型不再覆盖（ADR-003 D4）。 */
    fun updateSoftwareVersion(v: String) {
        applySwVersion(v)
        _uiState.update { it.copy(isSwVersionCustom = true) }
        clearStaleQueryResult()
    }

    /** 自动填充 / 刷回 → 清脏，恢复「自动跟随机型」状态（ADR-003 D4）。 */
    private fun fillSwVersion(v: String) {
        applySwVersion(v)
        _uiState.update { it.copy(isSwVersionCustom = false) }
    }

    /**
     * 「用推荐版本」按钮：用当前选中机型的推荐版本号刷新输入框，并恢复自动跟随。
     * 手动模式或该机型未配置时静默不作为（ADR-003 D5）。
     */
    fun applyRecommendedSwVersion() {
        val state = _uiState.value
        if (state.manualMode) return
        val device = VivoDeviceDatabase.devicesOf(state.selectedSeries)
            .getOrNull(state.selectedModelIndex) ?: return
        if (device.defaultSwVersion.isBlank()) return
        fillSwVersion(device.defaultSwVersion)
        clearStaleQueryResult()
    }

    /**
     * 从「可选版本号数组」下拉选择某个版本号（ADR-扩展）。
     * 视为用户从机型官方版本中挑选，清脏，恢复自动跟随（与 applyRecommendedSwVersion 一致）。
     */
    fun applyOptionalSwVersion(v: String) {
        fillSwVersion(v)
        clearStaleQueryResult()
    }

    fun updateAndroidVersion(v: Int) {
        _uiState.update { it.copy(androidVersion = v, isCustomAndroidVersion = false) }
        clearStaleQueryResult()
    }

    fun selectCustomAndroidVersion() {
        _uiState.update { it.copy(isCustomAndroidVersion = true) }
        clearStaleQueryResult()
    }

    fun updateCustomAndroidVersion(v: String) {
        val num = v.filter { it.isDigit() }
        val ver = num.toIntOrNull() ?: 0
        _uiState.update { it.copy(customAndroidVersion = num, androidVersion = if (ver > 0) ver else it.androidVersion, isCustomAndroidVersion = true) }
        clearStaleQueryResult()
    }

    fun updateSn(v: String) {
        _uiState.update { it.copy(sn = v) }
        clearStaleQueryResult()
    }
    fun updateQueryChannel(channel: String) {
        _uiState.update {
            // 尝鲜 / 公测 / 内测通道仅支持增量包，强制锁定为增量
            if (channel != "NORMAL") {
                it.copy(queryChannel = channel, isFullPackage = false)
            } else {
                it.copy(queryChannel = channel)
            }
        }
        clearStaleQueryResult()
    }

    fun updateQueryDomain(domain: String) {
        if (domain !in listOf("CN", "GLOBAL")) return
        _uiState.update { it.copy(queryDomain = domain) }
        clearStaleQueryResult()
    }

    fun updateDeviceType(type: String) {
        _uiState.update { it.copy(deviceType = type) }
        clearStaleQueryResult()
    }
    fun togglePackageType() {
        _uiState.update {
            // 尝鲜 / 公测 / 内测通道下禁止切换包类型，始终保持增量
            if (it.queryChannel != "NORMAL") it else it.copy(isFullPackage = !it.isFullPackage)
        }
        clearStaleQueryResult()
    }
    fun toggleManualMode() {
        _uiState.update { it.copy(manualMode = !it.manualMode) }
        clearStaleQueryResult()
    }
    fun updateManualCodename(v: String) {
        _uiState.update { it.copy(manualCodename = v) }
        clearStaleQueryResult()
    }
    fun updateManualModelSwVer(v: String) {
        _uiState.update { it.copy(manualModelSwVer = v) }
        clearStaleQueryResult()
    }
    fun updateManualModelName(v: String) {
        _uiState.update { it.copy(manualModelName = v) }
        clearStaleQueryResult()
    }
    fun clearToast() { _uiState.update { it.copy(toastMessage = null) } }

    fun deleteHistoryEntry(timestamp: Long) {
        val updated = _uiState.value.history.filterNot { it.timestamp == timestamp }
        _uiState.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    fun toggleHistorySelectionMode() {
        _uiState.update { it.copy(historySelectionMode = !it.historySelectionMode, selectedHistory = emptySet()) }
    }

    fun toggleHistorySelection(timestamp: Long) {
        _uiState.update {
            val newSet = if (timestamp in it.selectedHistory) it.selectedHistory - timestamp else it.selectedHistory + timestamp
            it.copy(selectedHistory = newSet)
        }
    }

    fun selectAllHistory() {
        val all = _uiState.value.history.map { it.timestamp }.toSet()
        _uiState.update { it.copy(selectedHistory = all) }
    }

    fun deleteSelectedHistory() {
        val selected = _uiState.value.selectedHistory
        val updated = _uiState.value.history.filterNot { it.timestamp in selected }
        _uiState.update { it.copy(history = updated, selectedHistory = emptySet(), historySelectionMode = false) }
        saveHistory(updated)
    }

    fun isAllHistorySelected(): Boolean {
        return _uiState.value.history.isNotEmpty() &&
            _uiState.value.history.all { it.timestamp in _uiState.value.selectedHistory }
    }

    private fun fetchChangelog(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = client.fetchChangelog(url)
            val ctx = AndroidAppContext.getApplicationContext()!!
            _uiState.update { it.copy(changelogContent = content ?: ctx.getString(R.string.no_changelog)) }
        }
    }

    /**
     * 从历史记录按需读取更新日志：纯读取，不写入全局 changelogContent，
     * 由独立的日志页自行管理加载状态，避免与查询结果的版本号混淆。
     */
    suspend fun getChangelog(url: String): String? = withContext(Dispatchers.IO) {
        client.fetchChangelog(url)
    }


    /**
     * 把偏好系列名映射到设备库中真实存在的键。
     * 内置数据与远端数据的命名不一致（"X 系列" vs "VIVO X 系列"），
     * 去品牌前缀后比较；仍失败再做带词边界的包含匹配，最后回退到第一个系列。
     */
    private fun resolveSeries(candidates: List<String>, preferred: String): String {
        if (preferred in candidates) return preferred
        // 去品牌前缀后比较：远端数据的 "VIVO X 系列" / "IQOO 旗舰系列" 与内置 "X 系列" / "iQOO 旗舰系列" 归一
        val target = VivoDeviceDatabase.normalizeSeriesName(preferred)
        candidates.firstOrNull { VivoDeviceDatabase.normalizeSeriesName(it) == target }
            ?.let { return it }
        // 兜底包含匹配：必须落在词边界上。
        // 否则 "X 系列" 会命中 "NEX 系列"（N-E-X-空格-系-列，X 后正好是空格），
        // 而 NEX 系列在列表中排在 VIVO X 系列之前，firstOrNull 会选错。
        candidates.firstOrNull {
            val idx = it.indexOf(target, ignoreCase = true)
            idx >= 0 && (idx == 0 || !it[idx - 1].isLetterOrDigit())
        }?.let { return it }
        return candidates.first()
    }

    private fun detectDeviceType(series: String): String {
        return if (series.contains("平板") || series.contains("穿戴")) "tablet" else "phone"
    }

    /** 检索条件变化后，旧结果/旧错误不再可信，立即清除避免误导。 */
    private fun clearStaleQueryResult() {
        _uiState.update { it.copy(result = null, error = null) }
    }

    fun query() {
        val state = _uiState.value
        if (!state.cryptoReady) return

        val codename: String
        val modelSwVer: String
        val modelName: String

        if (state.manualMode) {
            codename = state.manualCodename.trim()
            modelSwVer = state.manualModelSwVer.trim()
            modelName = state.manualModelName.trim().ifEmpty { codename }
            if (codename.isEmpty()) return
        } else {
            codename = state.selectedCodename
            modelSwVer = state.selectedModelSwVer
            modelName = state.selectedModel
            if (codename.isEmpty()) return
        }
        if (state.softwareVersion.isEmpty()) return

        _uiState.update { it.copy(isLoading = true, error = null, result = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = client.query(
                    codename = codename,
                    modelSwVer = modelSwVer,
                    swVersion = state.softwareVersion,
                    androidVersion = state.androidVersion,
                    isPhone = state.deviceType == "phone",
                    isFull = state.isFullPackage,
                    sn = state.sn,
                    imei = state.imei,
                    channel = VivoOtaClient.QueryChannel.valueOf(state.queryChannel),
                    domain = VivoOtaClient.Domain.valueOf(state.queryDomain)
                )

                val hasUpdate = result.updateVersion.isNotEmpty() &&
                        result.updateVersion != "(Not found)" &&
                        result.filename.isNotEmpty() &&
                        result.filename != "(Not found)"

                if (hasUpdate) {
                    val ctx = AndroidAppContext.getApplicationContext()!!
                    addToHistory(
                        model = modelName,
                        codename = codename,
                        swVersion = state.softwareVersion,
                        result = result,
                        querySoftwareVersion = state.softwareVersion,
                        manualMode = state.manualMode,
                        manualCodename = state.manualCodename,
                        manualModelSwVer = state.manualModelSwVer,
                        manualModelName = state.manualModelName,
                        androidVersion = state.androidVersion,
                        deviceType = state.deviceType,
                        isFullPackage = state.isFullPackage,
                        queryChannel = state.queryChannel,
                        queryDomain = state.queryDomain,
                        changelogUrl = result.changelogUrl
                    )
                    _uiState.update {
                        it.copy(isLoading = false, result = result, toastMessage = ctx.getString(R.string.toast_success))
                    }
                    if (result.changelogUrl.isNotEmpty() && result.changelogUrl != "(Not found)") {
                        _uiState.update { it.copy(changelogContent = "loading") }
                        fetchChangelog(result.changelogUrl)
                    }
                } else {
                    val ctx = AndroidAppContext.getApplicationContext()!!
                    _uiState.update {
                        it.copy(isLoading = false, result = result, toastMessage = ctx.getString(R.string.toast_no_update))
                    }
                }
            } catch (e: Exception) {
                Log.e("VivoOtaViewModel", "Query failed", e)
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun addToHistory(
        model: String,
        codename: String,
        swVersion: String,
        result: VivoOtaResult,
        querySoftwareVersion: String,
        manualMode: Boolean,
        manualCodename: String,
        manualModelSwVer: String,
        manualModelName: String,
        androidVersion: Int,
        deviceType: String,
        isFullPackage: Boolean,
        queryChannel: String,
        queryDomain: String,
        changelogUrl: String
    ) {
        val now = System.currentTimeMillis()
        val entry = QueryHistoryEntry(
            timestamp = now,
            model = model,
            codename = codename,
            swVersion = swVersion,
            resultVersion = result.updateVersion,
            fileSize = result.fileSizeMb,
            downloadUrl = result.downloadUrl,
            channel = result.channel,
            querySoftwareVersion = querySoftwareVersion,
            manualMode = manualMode,
            manualCodename = manualCodename,
            manualModelSwVer = manualModelSwVer,
            manualModelName = manualModelName,
            androidVersion = androidVersion,
            deviceType = deviceType,
            isFullPackage = isFullPackage,
            queryChannel = queryChannel,
            queryDomain = queryDomain,
            changelogUrl = changelogUrl
        )
        val current = _uiState.value.history.toMutableList()
        // 去重：以"查询条件签名"为键（而非 downloadUrl，后者常含动态签名/时间戳参数，每次都不同）。
        val sig = querySignature(entry)
        val dupIndex = current.indexOfFirst { querySignature(it) == sig }
        if (dupIndex >= 0) {
            current[dupIndex] = entry
        } else {
            current.add(entry)
        }
        // 按查询时间降序排序（最新在前），并截断到最多 20 条。
        val updated = current.sortedByDescending { it.timestamp }.take(20)
        _uiState.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    /**
     * 查询条件签名：把能唯一确定"这次查的是什么"的输入字段归一化成一个字符串。
     * 排除 timestamp / downloadUrl / 结果字段（这些每次都可能变化，不能作为判重依据）。
     * 相同查询条件必然生成相同签名，从而正确去重。
     */
    private fun querySignature(e: QueryHistoryEntry): String =
        listOf(
            e.manualMode,
            e.manualCodename.trim(),
            e.manualModelSwVer.trim(),
            e.manualModelName.trim(),
            e.querySoftwareVersion.trim(),
            e.androidVersion,
            e.deviceType.trim(),
            e.isFullPackage,
            e.queryChannel.trim(),
            e.queryDomain.trim()
        ).joinToString("|")

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        saveHistory(emptyList())
    }

    /**
     * 一键回填：把历史记录中的查询条件写回表单状态。
     * 仅填充表单，不自动发起查询，由用户自行点击查询按钮。
     */
    fun fillHistoryBack(entry: QueryHistoryEntry) {
        _uiState.update {
            it.copy(
                manualMode = entry.manualMode,
                manualCodename = entry.manualCodename,
                manualModelSwVer = entry.manualModelSwVer,
                manualModelName = entry.manualModelName,
                softwareVersion = entry.querySoftwareVersion,
                androidVersion = entry.androidVersion,
                deviceType = entry.deviceType,
                isFullPackage = entry.isFullPackage,
                queryChannel = entry.queryChannel,
                queryDomain = entry.queryDomain,
                // ADR-003 D6：历史回填的版本号视为用户明确选择，后续切机型不覆盖
                isSwVersionCustom = true
            )
        }
        clearStaleQueryResult()
    }

    private fun loadHistory() {
        try {
            val json = prefs.getString("history", "") ?: ""
            if (json.isEmpty()) return
            val arr = JSONArray(json)
            val list = mutableListOf<QueryHistoryEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    QueryHistoryEntry(
                        timestamp = o.getLong("timestamp"),
                        model = o.getString("model"),
                        codename = o.getString("codename"),
                        swVersion = o.getString("swVersion"),
                        resultVersion = o.optString("resultVersion", ""),
                        fileSize = o.optString("fileSize", ""),
                        downloadUrl = o.optString("downloadUrl", ""),
                        channel = o.optString("channel", "NORMAL"),
                        querySoftwareVersion = o.optString("querySoftwareVersion", ""),
                        manualMode = o.optBoolean("manualMode", false),
                        manualCodename = o.optString("manualCodename", ""),
                        manualModelSwVer = o.optString("manualModelSwVer", ""),
                        manualModelName = o.optString("manualModelName", ""),
                        androidVersion = o.optInt("androidVersion", 15),
                        deviceType = o.optString("deviceType", "phone"),
                        isFullPackage = o.optBoolean("isFullPackage", true),
                        queryChannel = o.optString("queryChannel", "NORMAL"),
                        queryDomain = o.optString("queryDomain", "CN"),
                        changelogUrl = o.optString("changelogUrl", "")
                    )
                )
            }
            // 兜底：按查询时间降序排序（最新在前），兼容早期未排序的存储数据。
            val sorted = list.sortedByDescending { it.timestamp }
            _uiState.update { it.copy(history = sorted) }
        } catch (e: Exception) {
            Log.w("VivoOtaViewModel", "Failed to load history", e)
        }
    }

    private fun saveHistory(list: List<QueryHistoryEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("timestamp", e.timestamp)
                    put("model", e.model)
                    put("codename", e.codename)
                    put("swVersion", e.swVersion)
                    put("resultVersion", e.resultVersion)
                    put("fileSize", e.fileSize)
                    put("downloadUrl", e.downloadUrl)
                    put("channel", e.channel)
                    put("querySoftwareVersion", e.querySoftwareVersion)
                    put("manualMode", e.manualMode)
                    put("manualCodename", e.manualCodename)
                    put("manualModelSwVer", e.manualModelSwVer)
                    put("manualModelName", e.manualModelName)
                    put("androidVersion", e.androidVersion)
                    put("deviceType", e.deviceType)
                    put("isFullPackage", e.isFullPackage)
                    put("queryChannel", e.queryChannel)
                    put("queryDomain", e.queryDomain)
                    put("changelogUrl", e.changelogUrl)
                })
            }
            prefs.edit().putString("history", arr.toString()).apply()
        } catch (e: Exception) {
            Log.w("VivoOtaViewModel", "Failed to save history", e)
        }
    }

    fun formatTime(ts: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

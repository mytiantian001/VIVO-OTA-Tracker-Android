package io.github.long36708.updater.vivo

/** IMEI 输入框当前值的来源，决定卡片里显示的提示文案。 */
enum class ImeiSource {
    /** 自动读取到的本机 IMEI。 */
    DEVICE,

    /** 本机读不到时自动补的随机值。 */
    RANDOM,

    /** 用户手动填写。 */
    MANUAL
}

data class VivoOtaResult(
    val updateVersion: String = "",
    val filename: String = "",
    val fileSizeBytes: String = "",
    val fileSizeMb: String = "",
    val downloadUrl: String = "",
    val changelogUrl: String = "",
    val securityPatch: String = "",
    val updateDate: String = "",
    val md5: String = "",
    val channel: String = "NORMAL",
    val rawResponse: String = ""
)

data class QueryHistoryEntry(
    val timestamp: Long,
    val model: String,
    val codename: String,
    val swVersion: String,
    val resultVersion: String,
    val fileSize: String,
    val downloadUrl: String,
    val channel: String = "NORMAL",
    // ===== 查询条件（用于一键回填表单）=====
    val querySoftwareVersion: String = "",
    val manualMode: Boolean = false,
    val manualCodename: String = "",
    val manualModelSwVer: String = "",
    val manualModelName: String = "",
    val androidVersion: Int = 15,
    val deviceType: String = "phone",
    val isFullPackage: Boolean = true,
    val queryChannel: String = "NORMAL",
    val queryDomain: String = "CN",
    val changelogUrl: String = ""
)

data class VivoOtaUiState(
    val selectedSeries: String = "X 系列",
    val selectedModelIndex: Int = 0,
    val selectedModel: String = "",
    val selectedCodename: String = "",
    val selectedModelSwVer: String = "",
    val deviceType: String = "phone",
    val softwareVersion: String = "15.0.33.7.W10",
    // ADR-003 D2：false = 版本号仍处于「自动跟随机型」状态，切机型时可被覆盖；
    //            true  = 用户手动编辑过，此后切机型一律不覆盖。
    val isSwVersionCustom: Boolean = false,
    val androidVersion: Int = 15,
    val isCustomAndroidVersion: Boolean = false,
    val customAndroidVersion: String = "",
    val sn: String = "A0000000000000A",
    val imei: String = "",
    val imeiSource: ImeiSource = ImeiSource.RANDOM,
    val isFullPackage: Boolean = true,
    val queryChannel: String = "NORMAL",
    val queryDomain: String = "CN",
    val isLoading: Boolean = false,
    val result: VivoOtaResult? = null,
    val error: String? = null,
    val cryptoReady: Boolean = false,
    val manualMode: Boolean = false,
    val manualCodename: String = "",
    val manualModelSwVer: String = "",
    val manualModelName: String = "",
    val history: List<QueryHistoryEntry> = emptyList(),
    val historySelectionMode: Boolean = false,
    val selectedHistory: Set<Long> = emptySet(),
    val toastMessage: String? = null,
    val changelogContent: String? = null
)

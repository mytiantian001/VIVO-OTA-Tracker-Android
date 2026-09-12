package io.github.long36708.updater.vivo.payload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.long36708.updater.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VivoPayloadViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PayloadDumperUiState())
    val uiState: StateFlow<PayloadDumperUiState> = _uiState

    private val _toastEvent = MutableSharedFlow<PayloadToast>(extraBufferCapacity = 20)
    val toastEvent = _toastEvent.asSharedFlow()

    private var currentPayload: Payload? = null

    // ===== zip 容器内文件浏览（ADR-001 / L0）=====

    private val _zipState = MutableStateFlow(ZipBrowserState())
    val zipState: StateFlow<ZipBrowserState> = _zipState

    /**
     * 独立于 toastEvent 的消息通道。
     * 不新增 PayloadToast 分支——Screen 里对它的 when 是穷尽匹配，加分支会编译失败。
     */
    private val _zipMessage = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val zipMessage = _zipMessage.asSharedFlow()

    /**
     * 串行化全部 zip 侧 HTTP 操作。
     * VivoPayloadHttpUtil 是单例且只有一个 position 游标，并发读写会互相踩踏（技术债 4）。
     */
    private val zipMutex = Mutex()

    /**
     * 嵌套导航栈：栈顶为当前层级。
     * 栈底是外层 OTA 包（HttpByteSource），其余是已读出到内存的内层 zip。
     */
    private val zipStack = ArrayDeque<ZipLevel>()

    private data class ZipLevel(val displayName: String, val source: ZipByteSource)

    /**
     * 当前解析目标的根字节源（ADR-004 D6）。
     *
     * 分区列表解析与「包内文件」共用同一个带块缓存的 source，使尾部 EOCD 与
     * central directory 在一次会话里只读一次；换链接时必须重建，否则旧偏移
     * 会打到新包的字节上。
     */
    private var rootSource: ZipByteSource? = null

    /** 取（必要时创建）当前目标的根源，保证分区解析与 zip 浏览共享缓存。 */
    private fun acquireRootSource(): ZipByteSource {
        rootSource?.let { return it }
        val created = CachingByteSource(HttpByteSource(VivoPayloadHttpUtil))
        rootSource = created
        return created
    }

    /**
     * 当前 `_zipState.entries` 所属的解析目标。
     *
     * 条目的 localHeaderOffset 只对生成它的那个包有效。换链接后若沿用旧条目，
     * 旧偏移会打到新包的字节上（甚至直接越界），表现为莫名其妙的「条目头损坏」。
     */
    private var zipSourceKey: String = ""

    /** 丢弃「包内文件」的全部状态：条目、嵌套栈、预览、搜索词。 */
    private fun resetZipBrowser() {
        zipStack.clear()
        zipSourceKey = ""
        rootSource = null
        _zipState.value = ZipBrowserState()
    }

    /** 输入框中的链接文本，供界面双向绑定。 */
    fun updateInputUrl(url: String) {
        _uiState.value = _uiState.value.copy(inputUrl = url)
    }

    /** 一键清空输入框中的链接。 */
    fun clearInputUrl() {
        _uiState.value = _uiState.value.copy(inputUrl = "")
    }

    /** 在输入框中手动提交链接并解析。 */
    fun submitUrl() {
        val target = _uiState.value.inputUrl.trim()
        if (target.isEmpty()) return
        // 把输入框内容设为解析目标，并清掉上一次的结果，确保用新地址解析。
        _uiState.value = _uiState.value.copy(
            pathOrUrl = target,
            parseRequestId = _uiState.value.parseRequestId + 1,
            isParsing = false,
            error = null,
            archiveInfo = null,
            partitions = emptyList(),
            filteredPartitions = emptyList(),
            selectedPartitions = emptySet(),
            selectedPartition = null
        )
    }

    /** 从查询结果直接带入下载链接，界面打开后自动解析。 */
    fun parseFromUrl(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        if (_uiState.value.pathOrUrl == target && _uiState.value.partitions.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(
            pathOrUrl = target,
            inputUrl = target,
            parseRequestId = _uiState.value.parseRequestId + 1,
            isParsing = false,
            error = null,
            archiveInfo = null,
            partitions = emptyList(),
            filteredPartitions = emptyList(),
            selectedPartitions = emptySet(),
            selectedPartition = null
        )
    }

    fun selectPartition(partitionName: String) {
        val partition = _uiState.value.partitions.find { it.partitionName == partitionName }
        _uiState.value = _uiState.value.copy(selectedPartition = partition)
    }

    fun clearSelectedPartition() {
        _uiState.value = _uiState.value.copy(selectedPartition = null)
    }

    fun toggleSelection(partitionName: String) {
        val current = _uiState.value.selectedPartitions
        _uiState.value = _uiState.value.copy(
            selectedPartitions = if (current.contains(partitionName)) {
                current - partitionName
            } else {
                current + partitionName
            }
        )
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedPartitions = _uiState.value.partitions.map { it.partitionName }.toSet()
        )
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedPartitions = emptySet())
    }

    fun isAllSelected(): Boolean {
        val state = _uiState.value
        return state.partitions.isNotEmpty() &&
            state.selectedPartitions.size == state.partitions.size
    }

    fun parsePayload(url: String) {
        val target = url.trim()
        viewModelScope.launch {
            // VivoPayloadHttpUtil 是单游标单例，zip 浏览也用它；统一用 zipMutex 串行，避免并发踩踏。
            zipMutex.withLock {
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    error = "Only online URL is supported"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                pathOrUrl = target,
                inputUrl = target,
                isParsing = true,
                error = null,
                archiveInfo = null,
                partitions = emptyList(),
                filteredPartitions = emptyList(),
                selectedPartitions = emptySet(),
                selectedPartition = null
            )
            // 重新解析即更换目标：包内文件列表属于上一个包，必须整体作废。
            // 之后把 zipSourceKey 认到当前 target，使稍后展开「包内文件」时
            // 不会再次 reset，从而复用本次解析建立的块缓存（ADR-004 D6）。
            resetZipBrowser()
            zipSourceKey = target
            try {
                Log.i("VivoPayload", "parseFromUrl: start, target=$target")
                VivoPayloadHttpUtil.init(target)
                Log.i("VivoPayload", "parseFromUrl: http init done, fileLength=${VivoPayloadHttpUtil.length()}, fileName=${VivoPayloadHttpUtil.getFileName()}")
                // ADR-004 D1/D3：zip 解析统一走 ZipByteSource，不再用单例游标 + 固定 256KB 缓冲
                // ADR-004 D6：与「包内文件」共用带块缓存的根源，central directory 只读一次
                val payload = PayloadUtil.initPayload(
                    VivoPayloadHttpUtil.getFileName(),
                    acquireRootSource()
                ).copy(sourcePath = target)

                currentPayload = payload
                Log.i("VivoPayload", "parseFromUrl: initPayload done, manifest partitions=${payload.deltaArchiveManifest.partitionsList.size}, blockSize=${payload.deltaArchiveManifest.blockSize}")
                val partitionList = PayloadUtil.getPartitionInfoList(payload)
                Log.i("VivoPayload", "parseFromUrl: getPartitionInfoList done, size=${partitionList.size}")
                val manifest = payload.deltaArchiveManifest
                val archiveInfo = ArchiveInfo(
                    fileName = payload.fileName,
                    fileSize = payload.archiveSize,
                    securityPatchLevel = manifest.securityPatchLevel,
                    buildDate = formatTimestamp(manifest.maxTimestamp),
                    blockSize = manifest.blockSize,
                    partialUpdate = manifest.partialUpdate,
                    minorVersion = manifest.minorVersion
                )

                // ADR-004 D9：把实际流量摆给用户看，证明「没有下载整包」
                val trafficBytes = VivoPayloadHttpUtil.bytesRead()
                Log.i("VivoPayload", "parseFromUrl: 本次读取流量 ${trafficBytes}B (包体 ${payload.archiveSize}B)")

                _uiState.value = _uiState.value.copy(
                    archiveInfo = archiveInfo,
                    partitions = partitionList,
                    filteredPartitions = partitionList,
                    isParsing = false,
                    readTrafficBytes = trafficBytes
                )
            } catch (e: Exception) {
                val msg = mapErrorMessage(e.message)
                Log.e("VivoPayload", "parseFromUrl FAILED: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    error = msg
                )
                _toastEvent.emit(PayloadToast.Error(msg))
            }
            }
        }
    }

    /**
     * 把底层解析异常映射为用户友好的提示文案。
     * 解析层用特定标记文案区分错误类型，这里转成易懂的中文说明。
     */
    private fun mapErrorMessage(raw: String?): String {
        return when (raw) {
            "NOT_A_PAYLOAD_ZIP" ->
                "这不是 A/B 增量包（payload.bin 不存在）。该 OTA 包可能是 recovery 全量包，无法用此工具解析。"
            "NOT_A_VALID_ZIP" ->
                "链接指向的文件不是有效的 OTA zip。可能原因：链接不正确、文件不完整、服务器返回了错误页面（如 HTML 而非 zip），或文件需要登录才能访问。请检查链接是否指向直链下载地址。"
            // ADR-004 D8：远程无法随机解压，必须给出可操作的提示
            "PAYLOAD_NOT_STORED" ->
                "该包的 payload.bin 是压缩存放，无法在线随机读取。请将 OTA 包下载到本地后再解析。"
            "TRUNCATED_READ" ->
                "读取数据的过程中连接被中断，请检查网络后重试。"
            // ADR-004 D4：服务器/网络问题要单独说清，不能让人以为包坏了
            "RANGE_NOT_SUPPORTED", "RANGE_INVALID_OFFSET" ->
                "该下载服务器不支持分段读取（Range），无法在线解析。请更换直链，或把 OTA 包下载到本地后再解析。"
            "RANGE_MISMATCH" ->
                "服务器返回的数据区间与请求不符，已中止解析。请重试或更换直链。"
            "ZIP64_EXTRA_MISSING" ->
                "包内条目缺少 ZIP64 扩展信息，无法定位数据。建议下载到本地后用其他工具解析。"
            "SOURCE_CHANGED" ->
                "解析目标已切换（链接被更换），请重新解析后再查看。"
            // ADR-004 D7：重试 3 次仍失败，说明是网络而不是包的问题
            "NETWORK_ERROR" ->
                "网络请求失败（已自动重试 3 次）。请检查网络后重试，或换一个时间再试。"
            else -> raw ?: "解析失败，请重试"
        }
    }

    fun extractPartition(context: Context, partitionInfo: PartitionInfo) {
        val payload = currentPayload ?: return
        viewModelScope.launch {
            val success = doExtract(context, payload, partitionInfo)
            if (success) {
                _toastEvent.emit(PayloadToast.ExtractSuccess(partitionInfo.partitionName))
            } else {
                _toastEvent.emit(PayloadToast.ExtractFailed(partitionInfo.partitionName))
            }
        }
    }

    fun extractSelectedPartitions(context: Context) {
        val payload = currentPayload ?: return
        val selected = _uiState.value.selectedPartitions
            .mapNotNull { name -> _uiState.value.partitions.find { it.partitionName == name } }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExtracting = true)
            var success = 0
            var fail = 0
            for (partition in selected) {
                val ok = doExtract(context, payload, partition)
                if (ok) success++ else fail++
                if (ok) {
                    _toastEvent.emit(PayloadToast.ExtractSuccess(partition.partitionName))
                } else {
                    _toastEvent.emit(PayloadToast.ExtractFailed(partition.partitionName))
                }
            }
            _uiState.value = _uiState.value.copy(
                isExtracting = false,
                selectedPartitions = emptySet()
            )
            _toastEvent.emit(PayloadToast.BatchComplete(success, fail))
        }
    }

    private suspend fun doExtract(context: Context, payload: Payload, partitionInfo: PartitionInfo): Boolean {
        markDownloading(partitionInfo.partitionName, true)
        try {
            val safeName = payload.fileName
                .substringBefore('?')
                .removeSuffix(".zip")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "payload" }
            // ADR-002 修复：targetSdk=37 且仅有 INTERNET 权限，直写
            // Environment.getExternalStoragePublicDirectory 会 Permission denied。
            // 改为先落 App 私有目录（零权限），再流式搬进 MediaStore.Downloads。
            val tempDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "VivoOtaTracker/$safeName"
            )
            if (!tempDir.exists()) tempDir.mkdirs()
            val tempFile = File(tempDir, "${partitionInfo.partitionName}.img")

            val partitionUpdate = payload.deltaArchiveManifest.partitionsList.find {
                it.partitionName == partitionInfo.partitionName
            } ?: throw RuntimeException("Partition not found")

            // 单例 HTTP 只有一个 position 游标：提取分区必须与 zip 浏览/解析互斥，
            // 否则提取途中被预览改动游标，会静默读到错位数据（ADR-004 技术债 4）。
            zipMutex.withLock {
                PayloadUtil.extractPartition(
                    partitionUpdate,
                    VivoPayloadHttpUtil,
                    tempDir.absolutePath,
                    payload
                ) { progress ->
                    updateProgress(partitionInfo.partitionName, progress)
                }
            }

            val saved = saveImageToDownloads(context, "${partitionInfo.partitionName}.img", tempFile)
            tempFile.delete()
            markDownloading(partitionInfo.partitionName, false)
            return saved
        } catch (e: Exception) {
            markDownloading(partitionInfo.partitionName, false)
            return false
        }
    }

    private fun markDownloading(partitionName: String, downloading: Boolean) {
        _uiState.value = _uiState.value.copy(
            partitions = _uiState.value.partitions.map {
                if (it.partitionName == partitionName) {
                    it.copy(isDownloading = downloading, progress = 0f)
                } else it
            }
        )
        updateFilteredPartitions()
    }

    private fun updateProgress(partitionName: String, progress: Long) {
        _uiState.value = _uiState.value.copy(
            partitions = _uiState.value.partitions.map {
                if (it.partitionName == partitionName) {
                    it.copy(progress = progress.toFloat())
                } else it
            }
        )
        updateFilteredPartitions()
    }

    private fun updateFilteredPartitions() {
        _uiState.value = _uiState.value.copy(
            filteredPartitions = _uiState.value.partitions
        )
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(timestamp * 1000L))
        }.getOrDefault("")
    }

    // ===== zip 容器内文件浏览（ADR-001 / L0 层）=====

    /** 展开「包内文件」时按需加载；已加载过则不再发请求。 */
    fun loadZipEntries(context: Context) {
        viewModelScope.launch {
            zipMutex.withLock {
                // 解析目标变了（换了链接）：旧条目一律作废，否则偏移会张冠李戴
                val target = _uiState.value.pathOrUrl
                if (zipSourceKey != target) {
                    resetZipBrowser()
                    zipSourceKey = target
                }
                if (_zipState.value.entries.isNotEmpty()) return@withLock
                _zipState.value = _zipState.value.copy(isLoading = true, error = null)
                try {
                    val rootName = VivoPayloadHttpUtil.getFileName()
                        .ifBlank { "ota.zip" }
                    zipStack.clear()
                    zipStack.addLast(ZipLevel(rootName, acquireRootSource()))
                    val entries = VivoZipBrowser.listZipEntries(zipStack.last().source)
                    _zipState.value = _zipState.value.copy(
                        entries = entries,
                        rootItems = computeRootItems(entries),
                        viewEntries = emptyList(),
                        currentFolder = null,
                        searchQuery = "",
                        breadcrumb = listOf(rootName),
                        isLoading = false
                    )
                } catch (e: Exception) {
                    zipStack.clear()
                    _zipState.value = _zipState.value.copy(
                        isLoading = false,
                        error = context.getString(mapZipErrorRes(e.message))
                    )
                }
            }
        }
    }

    /**
     * 进入嵌套 zip。
     *
     * 内层 zip 必须完整读出才能定位其 central directory（DEFLATE 无法部分解压），
     * 因此仍需 ≤10MB 才行——与下载红线一致。
     */
    fun openNestedZip(context: Context, entry: ZipEntryInfo) {
        viewModelScope.launch {
            zipMutex.withLock {
                if (zipStack.size >= ZipEntryInfo.MAX_NESTED_DEPTH) {
                    _zipMessage.emit(context.getString(R.string.zip_nested_too_deep))
                    return@withLock
                }
                if (!entry.canDownload) {
                    _zipMessage.emit(context.getString(R.string.zip_download_too_large))
                    return@withLock
                }
                _zipState.value = _zipState.value.copy(isLoading = true, error = null)
                try {
                    val data = VivoZipBrowser.readEntryBytes(
                        zipStack.last().source, entry, ZipEntryInfo.DOWNLOAD_LIMIT_BYTES
                    )
                    if (!data.complete) {
                        _zipState.value = _zipState.value.copy(
                            isLoading = false,
                            error = context.getString(R.string.zip_download_incomplete)
                        )
                        return@withLock
                    }
                    val nested = MemoryByteSource(data.bytes)
                    val entries = VivoZipBrowser.listZipEntries(nested)
                    val displayName = entry.name.substringAfterLast('/').ifBlank { entry.name }
                    zipStack.addLast(ZipLevel(displayName, nested))
                    _zipState.value = _zipState.value.copy(
                        entries = entries,
                        rootItems = computeRootItems(entries),
                        viewEntries = emptyList(),
                        currentFolder = null,
                        searchQuery = "",
                        breadcrumb = zipStack.map { it.displayName },
                        isLoading = false
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(
                        isLoading = false,
                        error = context.getString(mapZipErrorRes(e.message))
                    )
                }
            }
        }
    }

    /** 返回上一层；已在最外层则不作为。父级数据在内存中，无需重新下载。 */
    fun navigateZipUp(context: Context) {
        viewModelScope.launch {
            zipMutex.withLock {
                if (zipStack.size <= 1) return@withLock
                zipStack.removeLast()
                _zipState.value = _zipState.value.copy(isLoading = true, error = null)
                try {
                    val entries = VivoZipBrowser.listZipEntries(zipStack.last().source)
                    _zipState.value = _zipState.value.copy(
                        entries = entries,
                        rootItems = computeRootItems(entries),
                        viewEntries = emptyList(),
                        currentFolder = null,
                        searchQuery = "",
                        breadcrumb = zipStack.map { it.displayName },
                        isLoading = false
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(
                        isLoading = false,
                        error = context.getString(mapZipErrorRes(e.message))
                    )
                }
            }
        }
    }

    /** 供界面判断返回键应退出文件夹还是退出嵌套包。 */
    fun canExitZipFolder(): Boolean = _zipState.value.currentFolder != null
    fun canNavigateZipUp(): Boolean = zipStack.size > 1

    // ===== ADR-002：文件管理器式浏览 =====

    /**
     * 合成顶层文件夹：按路径首段聚合。
     *
     * zip 中不存在显式目录条目，文件夹全靠路径前缀合成（ADR-002 D2）。
     * 只合成**一层**——深层内容进入后平铺，避免逐级点入深路径。
     */
    private fun computeRootItems(entries: List<ZipEntryInfo>): List<ZipListItem> {
        val folders = LinkedHashMap<String, Pair<Int, Long>>()
        val loose = ArrayList<ZipEntryInfo>()
        for (e in entries) {
            if (e.isDirectory) continue
            val idx = e.name.indexOf('/')
            if (idx < 0) {
                loose.add(e)
            } else {
                val top = e.name.substring(0, idx)
                val (count, size) = folders[top] ?: (0 to 0L)
                folders[top] = (count + 1) to (size + e.uncompressedSize)
            }
        }
        val items = ArrayList<ZipListItem>()
        folders.entries.sortedBy { it.key }
            .forEach { (name, v) -> items.add(ZipListItem.Folder(name, v.first, v.second)) }
        loose.sortedBy { it.name }.forEach { items.add(ZipListItem.Entry(it)) }
        return items
    }

    /**
     * 当前视图的文件条目。
     *
     * 搜索覆盖**整个当前 zip 层**（含子目录内的条目），否则用户搜
     * `metadata` 会因为它在 META-INF 里而搜不到，这比搜不到更反直觉。
     * 「只搜当前层」指的是不搜未打开的嵌套 zip（ADR-002 D3）。
     */
    private fun computeViewEntries(
        entries: List<ZipEntryInfo>,
        folder: String?,
        query: String
    ): List<ZipEntryInfo> {
        val base = if (query.isNotBlank()) {
            entries.filter { !it.isDirectory }
        } else if (folder != null) {
            entries.filter { !it.isDirectory && it.name.startsWith("$folder/") }
        } else {
            emptyList()
        }
        val filtered = if (query.isBlank()) {
            base
        } else {
            base.filter { it.name.contains(query, ignoreCase = true) }
        }
        return filtered.sortedBy { it.name }
    }

    /** 进入合成文件夹。 */
    fun enterZipFolder(folder: String) {
        val state = _zipState.value
        _zipState.value = state.copy(
            currentFolder = folder,
            viewEntries = computeViewEntries(state.entries, folder, state.searchQuery)
        )
    }

    /** 返回顶层；已进入最外层 zip 时由调用方处理退出。 */
    fun exitZipFolder() {
        val state = _zipState.value
        _zipState.value = state.copy(currentFolder = null)
    }

    fun updateZipSearch(query: String) {
        val state = _zipState.value
        val q = query.trim()
        _zipState.value = state.copy(
            searchQuery = query,
            viewEntries = computeViewEntries(state.entries, state.currentFolder, q)
        )
    }

    /**
     * 预览文本条目。
     *
     * @param loadFull false 时只取前 8KB——DEFLATE 场景实际流量约 2~4KB，
     *                 因为 zip 每条目独立压缩，读满即可掐断连接（ADR-001 D5）；
     *                 true 时取完整内容，仍受 10MB 红线约束。
     */
    fun previewEntry(context: Context, entry: ZipEntryInfo, loadFull: Boolean = false) {
        viewModelScope.launch {
            zipMutex.withLock {
                _zipState.value = _zipState.value.copy(
                    previewEntry = entry,
                    isPreviewLoading = true,
                    previewError = null,
                    preview = if (loadFull) _zipState.value.preview else null
                )
                try {
                    val source = zipStack.lastOrNull()?.source
                    if (source == null) {
                        _zipState.value = _zipState.value.copy(isPreviewLoading = false)
                        return@withLock
                    }
                    val maxOutput = if (loadFull) {
                        ZipEntryInfo.DOWNLOAD_LIMIT_BYTES
                    } else {
                        ZipEntryInfo.PREVIEW_BYTES.toLong()
                    }
                    val maxInput = if (loadFull) {
                        Long.MAX_VALUE
                    } else {
                        VivoZipBrowser.PREVIEW_INPUT_BYTES.toLong()
                    }
                    val data = VivoZipBrowser.readEntryBytes(source, entry, maxOutput, maxInput)

                    // 签名块（CERT.RSA 等）是 DER 二进制，走证书解析而非文本嗅探
                    if (entry.isSignatureBlock) {
                        val desc = VivoZipBrowser.decodeSignatureBlock(data.bytes)
                            ?: context.getString(R.string.zip_preview_not_text)
                        _zipState.value = _zipState.value.copy(
                            isPreviewLoading = false,
                            preview = TextPreview(
                                text = desc,
                                encoding = "DER / X.509",
                                bytesLoaded = data.bytes.size.toLong(),
                                totalSize = entry.uncompressedSize,
                                truncated = false,
                                canCopyAll = desc.toByteArray().size <= ZipEntryInfo.CLIPBOARD_LIMIT_BYTES
                            )
                        )
                        return@withLock
                    }

                    // ADR-001 D6：白名单只决定「是否给入口」，能否预览由内容嗅探拍板。
                    // 这样无扩展名的 metadata / updater-script 能进，而 .pb 会被 NUL 拦截。
                    if (!VivoZipBrowser.looksLikeText(data.bytes)) {
                        _zipState.value = _zipState.value.copy(
                            isPreviewLoading = false,
                            previewError = context.getString(R.string.zip_preview_not_text)
                        )
                        return@withLock
                    }
                    val (text, encoding) = VivoZipBrowser.decodeText(data.bytes)
                    val truncated = !data.complete
                    _zipState.value = _zipState.value.copy(
                        isPreviewLoading = false,
                        preview = TextPreview(
                            text = text,
                            encoding = encoding,
                            bytesLoaded = data.bytes.size.toLong(),
                            totalSize = entry.uncompressedSize,
                            truncated = truncated,
                            // 截断时复制的是「已加载部分」，仍允许（ADR-001 D9）
                            canCopyAll = text.toByteArray().size <= ZipEntryInfo.CLIPBOARD_LIMIT_BYTES
                        )
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(
                        isPreviewLoading = false,
                        previewError = context.getString(mapZipErrorRes(e.message))
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        _zipState.value = _zipState.value.copy(
            previewEntry = null,
            preview = null,
            previewError = null,
            isPreviewLoading = false
        )
    }

    /** ADR-001 D3/D12：≤10MB 才可下载，落盘后校验 CRC32 佐证流式 Range 拼接未错位。 */
    fun downloadEntry(context: Context, entry: ZipEntryInfo) {
        viewModelScope.launch {
            zipMutex.withLock {
                if (!entry.canDownload) {
                    _zipMessage.emit(context.getString(R.string.zip_download_too_large))
                    return@withLock
                }
                val source = zipStack.lastOrNull()?.source
                if (source == null) {
                    _zipMessage.emit(context.getString(R.string.zip_err_generic))
                    return@withLock
                }
                _zipState.value = _zipState.value.copy(downloadingEntryName = entry.name)
                val displayName = sanitizeFileName(entry.name.substringAfterLast('/'))
                try {
                    val data = VivoZipBrowser.readEntryBytes(
                        source, entry, ZipEntryInfo.DOWNLOAD_LIMIT_BYTES
                    )
                    // 产出被 10MB 上限截断 ⇒ 实际大小与 header 声明不符，按 zip bomb 处理。
                    // header 里的 uncompressedSize 是可伪造的，不能只信它。
                    if (!data.complete) {
                        _zipState.value = _zipState.value.copy(downloadingEntryName = null)
                        _zipMessage.emit(context.getString(R.string.zip_download_incomplete))
                        return@withLock
                    }
                    val crcOk = VivoZipBrowser.crc32(data.bytes) == entry.crc32
                    val saved = saveEntryToDownloads(context, displayName, data.bytes)
                    _zipState.value = _zipState.value.copy(downloadingEntryName = null)
                    _zipMessage.emit(
                        if (!saved) {
                            context.getString(R.string.zip_download_failed, displayName)
                        } else if (crcOk) {
                            context.getString(R.string.zip_download_success, displayName)
                        } else {
                            context.getString(R.string.zip_download_crc_mismatch, displayName)
                        }
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(downloadingEntryName = null)
                    _zipMessage.emit(context.getString(mapZipErrorRes(e.message)))
                }
            }
        }
    }

    /**
     * ADR-001 D4：Android 10+ 走 MediaStore.Downloads（零权限且用户可见），
     * 低版本回退 App 私有目录。文件均 ≤10MB，整块读入内存后一次性写出即可。
     */
    private fun saveEntryToDownloads(context: Context, displayName: String, data: ByteArray): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "VivoOtaTracker"
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, displayName).writeBytes(data)
                true
            }
        }.getOrDefault(false)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "entry" }
    }

    /**
     * 分区镜像通常很大（GB 级），不能整块读入内存。
     * 走 MediaStore.Downloads 分块流式写出（零权限、用户可见），
     * 低版本回退 App 私有目录。写入成功后由调用方负责删除源临时文件。
     */
    private fun saveImageToDownloads(context: Context, displayName: String, src: File): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input ->
                        val buf = ByteArray(8 * 1024 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                        }
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "VivoOtaTracker"
                )
                if (!dir.exists()) dir.mkdirs()
                src.copyTo(File(dir, displayName), overwrite = true)
                true
            }
        }.getOrDefault(false)
    }

    private fun mapZipErrorRes(raw: String?): Int {
        return when (raw) {
            "NOT_A_VALID_ZIP" -> R.string.zip_err_not_valid_zip
            "BAD_LOCAL_HEADER" -> R.string.zip_err_bad_local_header
            "BAD_DEFLATE_DATA" -> R.string.zip_err_bad_deflate
            "UNSUPPORTED_ENTRY_METHOD" -> R.string.zip_err_unsupported_method
            // ADR-004 D2/D4：把「解析/网络」问题与「包损坏」区分开
            "ZIP64_EXTRA_MISSING" -> R.string.zip_err_zip64_missing
            "RANGE_NOT_SUPPORTED", "RANGE_INVALID_OFFSET" -> R.string.zip_err_range_unsupported
            "RANGE_MISMATCH" -> R.string.zip_err_range_mismatch
            "SOURCE_CHANGED" -> R.string.zip_err_source_changed
            else -> R.string.zip_err_generic
        }
    }
}

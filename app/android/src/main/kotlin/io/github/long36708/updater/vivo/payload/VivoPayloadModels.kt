package io.github.long36708.updater.vivo.payload

import chromeos_update_engine.UpdateMetadata

data class Payload(
    val fileName: String,
    val header: PayloadHeader,
    val deltaArchiveManifest: UpdateMetadata.DeltaArchiveManifest,
    val dataOffset: Long,
    val blockSize: Int,
    val archiveSize: Long,
    val isPath: Boolean,
    val sourcePath: String = ""
)

data class PayloadHeader(
    val fileFormatVersion: Long,
    val manifestSize: Long,
    val metadataSignatureSize: Int,
)

data class PartitionInfo(
    val partitionName: String,
    val size: Long,
    val rawSize: Long,
    val sha256: String,
    val operationsCount: Int = 0,
    val mergeOperationsCount: Int = 0,
    val typeStats: Map<String, Int> = emptyMap(),
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
)

data class FileInfo(
    val offset: Long,
    val size: Long
)

data class ArchiveInfo(
    val fileName: String,
    val fileSize: Long,
    val securityPatchLevel: String,
    val buildDate: String = "",
    val blockSize: Int = 0,
    val partialUpdate: Boolean = false,
    val minorVersion: Int = 0
)

data class PayloadDumperUiState(
    val pathOrUrl: String = "",
    val inputUrl: String = "",
    val parseRequestId: Int = 0,
    val archiveInfo: ArchiveInfo? = null,
    val partitions: List<PartitionInfo> = emptyList(),
    val filteredPartitions: List<PartitionInfo> = emptyList(),
    val selectedPartitions: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isParsing: Boolean = false,
    val isExtracting: Boolean = false,
    val error: String? = null,
    val selectedPartition: PartitionInfo? = null,
    /** 本次解析实际从网络读取的字节数（ADR-004 D9），0 表示未统计。 */
    val readTrafficBytes: Long = 0
)

sealed class PayloadToast {
    data class ExtractSuccess(val partitionName: String) : PayloadToast()
    data class ExtractFailed(val partitionName: String) : PayloadToast()
    data class BatchComplete(val success: Int, val fail: Int) : PayloadToast()
    data class Error(val message: String) : PayloadToast()
}

// ===== zip 容器内文件浏览（ADR-001，L0 层）=====
// 层级定义见 docs/GLOSSARY.md：「列条目」(L0)、「提取」(L1)、「浏览」(L2) 三者不可混用。
// 注意：PayloadToast 的 when 在 Screen 里是穷尽匹配，zip 浏览的消息走独立的
// zipMessage 通道（纯字符串），不新增 PayloadToast 分支以免破坏现有穷尽性。

/** zip central directory 中的一条记录，本身不含任何文件内容。 */
data class ZipEntryInfo(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val method: Int,
    val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")

    val isStored: Boolean get() = method == METHOD_STORED
    val isDeflate: Boolean get() = method == METHOD_DEFLATE

    /** 仅支持 stored / deflate 两种方式。 */
    val isSupported: Boolean
        get() = !isDirectory && (isStored || isDeflate)

    /**
     * ADR-001 D3：压缩后（网络流量）与解压后（落盘占用）尺寸任一超过 10MB 即禁止下载。
     * 这只是前置判断，解压时另有实时产出校验防线（zip bomb 的 uncompressedSize 可伪造）。
     */
    val canDownload: Boolean
        get() = isSupported &&
            uncompressedSize > 0 &&
            compressedSize <= DOWNLOAD_LIMIT_BYTES &&
            uncompressedSize <= DOWNLOAD_LIMIT_BYTES

    /** ADR-001 D6 白名单初判，决定列表里是否提供「预览」入口；真正的判定在嗅探之后。 */
    val isTextByExtension: Boolean
        get() {
            val fileName = name.substringAfterLast('/')
            return TEXT_EXTENSIONS.any { fileName.endsWith(it, ignoreCase = true) } ||
                // OTA 包里若干关键文件没有扩展名，按文件名精确匹配
                TEXT_FILENAMES.any { it.equals(fileName, ignoreCase = true) }
        }

    /** 嵌套 zip：可继续展开浏览。 */
    val isNestedZip: Boolean
        get() = !isDirectory &&
            name.substringAfterLast('/').endsWith(".zip", ignoreCase = true)

    /** PKCS#7 签名块（CERT.RSA / CERT.DSA / CERT.EC）：按证书信息预览。 */
    val isSignatureBlock: Boolean
        get() = !isDirectory &&
            SIGNATURE_EXTENSIONS.any { name.substringAfterLast('/').endsWith(it, ignoreCase = true) }

    /**
     * 是否提供「预览」入口。
     * 文本走嗅探、签名块走证书解析、嵌套 zip 走展开浏览。
     */
    val canPreview: Boolean
        get() = isNestedZip || isSignatureBlock || (isSupported && isTextByExtension)

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8

        const val DOWNLOAD_LIMIT_BYTES = 10L * 1024 * 1024
        const val PREVIEW_BYTES = 8 * 1024
        /** Binder 事务上限约 1MB，留足余量。超过则不提供「复制全部」（ADR-001 D8）。 */
        const val CLIPBOARD_LIMIT_BYTES = 128 * 1024
        /** 嵌套层级上限，防 zip bomb 与无限递归。 */
        const val MAX_NESTED_DEPTH = 3

        private val TEXT_EXTENSIONS = listOf(
            ".txt", ".text", ".prop", ".properties", ".sh", ".rc", ".xml", ".json",
            ".cfg", ".conf", ".csv", ".log", ".md", ".ini", ".mf", ".sf", ".script"
        )

        /** OTA 包中无扩展名但确为纯文本的关键文件。 */
        private val TEXT_FILENAMES = listOf(
            "metadata",           // META-INF/com/android/metadata
            "updater-script",     // META-INF/com/google/android/updater-script
            "otacert",
            "metadata.pb.bin",
            "LICENSE", "NOTICE", "README", "CHANGELOG"
        )

        private val SIGNATURE_EXTENSIONS = listOf(".rsa", ".dsa", ".ec")
    }
}

/** 文本预览结果。 */
data class TextPreview(
    val text: String,
    val encoding: String,
    val bytesLoaded: Long,
    val totalSize: Long,
    /** 因 8KB 上限截断，可由用户按需「加载完整内容」。 */
    val truncated: Boolean,
    /** 是否可整段复制。受 Binder 1MB 上限约束，与 10MB 下载红线无关。 */
    val canCopyAll: Boolean,
)

/** 单条目下载结果。ADR-001 D12：带 CRC32 校验以佐证流式 Range 拼接未错位。 */
sealed class EntryDownloadOutcome {
    data class Success(val fileName: String, val size: Long, val crcOk: Boolean) : EntryDownloadOutcome()
    data class Failure(val reason: String) : EntryDownloadOutcome()
}

/**
 * 浏览列表中的一项（ADR-002 D2）。
 *
 * zip 里没有显式目录条目，Folder 是**按路径首段合成的**，
 * 不是 zip 固有结构——这个区分决定了「进入文件夹」只是视图过滤而非数据导航。
 */
sealed class ZipListItem {
    /** 合成的顶层文件夹。 */
    data class Folder(val name: String, val count: Int, val totalSize: Long) : ZipListItem()
    /** 文件条目。 */
    data class Entry(val entry: ZipEntryInfo) : ZipListItem()
}

/** zip 条目浏览的界面状态。 */
data class ZipBrowserState(
    /** 当前 zip 层的全部条目。 */
    val entries: List<ZipEntryInfo> = emptyList(),
    /** 顶层视图：合成文件夹在前、根目录散装文件在后。 */
    val rootItems: List<ZipListItem> = emptyList(),
    /** 文件夹内视图 / 搜索结果的条目。 */
    val viewEntries: List<ZipEntryInfo> = emptyList(),
    /** 当前所在文件夹；null 表示顶层视图。 */
    val currentFolder: String? = null,
    val searchQuery: String = "",
    /** zip 嵌套导航路径，首项为最外层包名。 */
    val breadcrumb: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val previewEntry: ZipEntryInfo? = null,
    val preview: TextPreview? = null,
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
    val downloadingEntryName: String? = null,
) {
    /** 顶层视图仅在「未进入文件夹且无搜索词」时使用。 */
    val isRootView: Boolean get() = currentFolder == null && searchQuery.isBlank()
}

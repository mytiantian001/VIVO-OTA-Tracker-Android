package io.github.long36708.updater.vivo.payload

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.min

/**
 * zip 容器内条目的读取与文本预览（ADR-001，L0 层）。
 * 术语与层级定义见 docs/GLOSSARY.md。
 *
 * 前提：OTA 包实测 4.3~11.6GB，任何路径都不得整包下载。
 * 依赖 zip「每条目独立压缩」的特性——DEFLATE 条目可单独顺序 inflate，
 * 无需下载其之前的任何数据，这是小流量预览成立的基础。
 */
/**
 * 可随机读取的字节源。
 *
 * 抽象出它是为了支持嵌套 zip：外层走 HTTP Range，内层则是已读出的内存缓冲。
 * 若解析函数直接依赖 VivoPayloadHttpUtil，内层 zip 将无从表达。
 */
interface ZipByteSource {
    val size: Long
    /** 从 offset 起尽量读满 out，返回实际读取字节数。 */
    suspend fun readAt(offset: Long, out: ByteArray): Int
}

/**
 * 在线 OTA 包：委托给全局 HTTP 单例（单游标，调用方需串行化）。
 *
 * 构造时快照「会话号 + 文件长度」：单例被 init 到别的链接后，本 source 立刻失效。
 * 否则旧包条目的 localHeaderOffset 会被拿去读新包的字节，读出来既不是本地头、
 * 长度对不上，还会被误报成「条目头损坏」——把状态污染说成数据损坏。
 */
class HttpByteSource(private val httpUtil: VivoPayloadHttpUtil) : ZipByteSource {
    private val session = httpUtil.sessionId()
    private val sessionLength = httpUtil.length()

    override val size: Long get() = sessionLength

    override suspend fun readAt(offset: Long, out: ByteArray): Int {
        if (httpUtil.sessionId() != session) throw IOException("SOURCE_CHANGED")
        httpUtil.seek(offset)
        var total = 0
        while (total < out.size) {
            val chunk = out.copyOfRange(total, out.size)
            val read = httpUtil.readSync(chunk)
            if (read <= 0) break
            System.arraycopy(chunk, 0, out, total, read)
            total += read
        }
        return total
    }
}

/**
 * zip 内某个条目的数据区间，映射为独立 source。
 *
 * 用途：把「OTA zip 里的 payload.bin」表达成一个起点为 0 的连续字节流，
 * 使 payload 头解析无需再关心它在 zip 中的绝对偏移（ADR-004 D3）。
 */
class SubrangeByteSource(
    private val base: ZipByteSource,
    private val baseOffset: Long,
    override val size: Long,
) : ZipByteSource {
    override suspend fun readAt(offset: Long, out: ByteArray): Int {
        if (offset < 0 || offset >= size) return 0
        val want = minOf(out.size.toLong(), size - offset).toInt()
        if (want == out.size) return base.readAt(baseOffset + offset, out)
        val tmp = ByteArray(want)
        val read = base.readAt(baseOffset + offset, tmp)
        System.arraycopy(tmp, 0, out, 0, read)
        return read
    }
}

/** 嵌套 zip：内容已完整读出到内存。 */
class MemoryByteSource(private val data: ByteArray) : ZipByteSource {
    override val size: Long get() = data.size.toLong()

    override suspend fun readAt(offset: Long, out: ByteArray): Int {
        if (offset < 0 || offset >= data.size) return 0
        val n = minOf(out.size.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), out, 0, n)
        return n
    }
}

object VivoZipBrowser {

    private const val TAG = "VivoZipBrowser"
    private const val CENSIG = 0x02014b50L
    private const val LOCSIG = 0x04034b50L
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL
    private const val ZIP64_EXTRA_ID = 0x0001

    private const val ENDSIG = 0x06054b50L
    private const val ENDHDR = 22
    private const val ZIP64_ENDSIG = 0x06064b50L
    private const val ZIP64_LOCSIG = 0x07064b50L
    private const val ZIP64_LOCHDR = 20

    /** central directory 异常膨胀时拒绝分配，避免 OOM。 */
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 8L * 1024 * 1024
    /**
     * local header 首读字节数（ADR-004 D5）。
     * 实测 vivo 的 extra field 可达数万字节，但 99% 的包头部不足 1KB，
     * 因此先读 1KB，不够再按 30+nameLen+extraLen 精确补读一次，
     * 而不是无脑读 256KB——那是分区列表路径长期多花的流量。
     */
    private const val LOCAL_HEADER_FIRST_READ_BYTES = 1024
    /** 预览最多读取的压缩数据量，换出 8KB 明文通常绰绰有余。 */
    const val PREVIEW_INPUT_BYTES = 64 * 1024

    data class EntryData(
        val bytes: ByteArray,
        /** 解压产出的真实字节数。 */
        val producedBytes: Long,
        /** 是否因输出上限而截断（预览场景属预期行为）。 */
        val truncatedByLimit: Boolean,
        /** 数据流是否完整结束。下载场景必须为 true，否则视为数据损坏。 */
        val complete: Boolean,
    )

    /**
     * 枚举 zip central directory 的全部条目。
     * 仅消耗「尾部 4KB + central directory」的流量，与包体大小无关。
     */
    suspend fun listZipEntries(source: ZipByteSource): List<ZipEntryInfo> =
        withContext(Dispatchers.IO) {
            val fileLength = source.size
            if (fileLength <= 0) throw IOException("NOT_A_VALID_ZIP")

            val tailSize = minOf(4096L, fileLength).toInt()
            val tail = ByteArray(tailSize)
            source.readAt(fileLength - tailSize, tail)

            val cen = locateCentralDirectory(tail, fileLength)
            if (cen.offset < 0 || cen.size <= 0 || cen.size > MAX_CENTRAL_DIRECTORY_BYTES) {
                throw IOException("NOT_A_VALID_ZIP")
            }
            Log.i(TAG, "listZipEntries: cenOffset=${cen.offset}, cenSize=${cen.size}")

            val central = ByteArray(cen.size.toInt())
            source.readAt(cen.offset, central)
            val entries = parseCentralDirectory(central)
            Log.i(TAG, "listZipEntries: parsed ${entries.size} entries")
            entries
        }

    /** A/B 增量包的 payload 条目名。OTA zip 中它普遍是第一个条目且为 STORED。 */
    internal const val PAYLOAD_ENTRY_SUFFIX = "payload.bin"

    internal fun findPayloadEntry(entries: List<ZipEntryInfo>): ZipEntryInfo? =
        entries.firstOrNull { it.name.endsWith(PAYLOAD_ENTRY_SUFFIX) }

    /**
     * 在尾部缓冲里定位 central directory（ADR-004 D1：由 PayloadUtil 迁入，全库一份）。
     *
     * @param byteArray 文件尾部缓冲（当前实现固定 4KB，故若 zip 注释超过 4KB 会失败——
     *                  实测 vivo 包注释为空，暂不引入更大回扫）
     */
    internal fun locateCentralDirectory(byteArray: ByteArray, fileLength: Long): FileInfo {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        val offset = byteBuffer.capacity() - ENDHDR
        var cenSize: Long = -1
        var cenOffset: Long = -1

        for (i in 0..byteBuffer.capacity() - ENDHDR) {
            byteBuffer.position(offset - i)
            if (byteBuffer.getInt().toLong() == ENDSIG) {
                val endSigOffset = byteBuffer.position()
                byteBuffer.position(byteBuffer.position() + 12)

                if (byteBuffer.getInt().toUInt().toLong() == ZIP64_MAGICVAL) {
                    // ZIP64：EOCD 里的 cenOffset 是占位符，真实值在 ZIP64 EOCD 里。
                    // 解析完必须 break——继续回扫只可能与注释里的偶然字节误匹配，
                    // 那样会拿到一段垃圾 cenOffset，把问题伪装成别的错误。
                    byteBuffer.position(endSigOffset - ZIP64_LOCHDR - 4)
                    if (byteBuffer.getInt().toLong() != ZIP64_LOCSIG) {
                        Log.w(TAG, "locateCentralDirectory: ZIP64 locator 签名不符")
                    } else {
                        byteBuffer.position(byteBuffer.position() + 4)
                        val zip64EndSigOffset = byteBuffer.getLong()
                        val tailIndex = byteArray.size - (fileLength - zip64EndSigOffset).toInt()
                        if (tailIndex < 0 || tailIndex + 56 > byteArray.size) {
                            Log.w(
                                TAG,
                                "locateCentralDirectory: ZIP64 EOCD @$zip64EndSigOffset " +
                                    "不在尾部 ${byteArray.size} 字节缓冲内"
                            )
                        } else {
                            byteBuffer.position(tailIndex)
                            if (byteBuffer.getInt().toLong() != ZIP64_ENDSIG) {
                                Log.w(TAG, "locateCentralDirectory: ZIP64 EOCD 签名不符")
                            } else {
                                byteBuffer.position(byteBuffer.position() + 36)
                                cenSize = byteBuffer.getLong().toULong().toLong()
                                cenOffset = byteBuffer.getLong().toULong().toLong()
                            }
                        }
                    }
                    break
                } else {
                    byteBuffer.position(endSigOffset + 8)
                    cenSize = byteBuffer.getInt().toUInt().toLong()
                    cenOffset = byteBuffer.getInt().toUInt().toLong()
                    break
                }
            }
        }
        return FileInfo(cenOffset, cenSize)
    }

    private fun parseCentralDirectory(bytes: ByteArray): List<ZipEntryInfo> {
        val entries = ArrayList<ZipEntryInfo>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buf.remaining() >= 4) {
            val sigPos = buf.position()
            if (buf.int.toLong() != CENSIG) {
                // 扫描式前进 1 字节：单字节错位不该让整段遍历失效（技术债 2）
                buf.position(sigPos + 1)
                continue
            }
            // 固定部分 46 字节，已消费 4
            if (buf.remaining() < 42) break

            val method = buf.getShort(sigPos + 10).toUInt().toInt()
            val crc32 = buf.getInt(sigPos + 16).toUInt().toLong()
            var compressedSize = buf.getInt(sigPos + 20).toUInt().toLong()
            var uncompressedSize = buf.getInt(sigPos + 24).toUInt().toLong()
            val nameLen = buf.getShort(sigPos + 28).toUInt().toInt()
            val extraLen = buf.getShort(sigPos + 30).toUInt().toInt()
            val commentLen = buf.getShort(sigPos + 32).toUInt().toInt()
            var localHeaderOffset = buf.getInt(sigPos + 42).toUInt().toLong()

            val recordEnd = sigPos + 46 + nameLen + extraLen + commentLen
            if (recordEnd > bytes.size) break

            val name = String(bytes, sigPos + 46, nameLen, Charsets.UTF_8)
            val extraStart = sigPos + 46 + nameLen
            val extra = bytes.copyOfRange(extraStart, extraStart + extraLen)

            // 技术债 5：≥4GB 的条目（recovery 包的 super.img 常 5~6GB）走 ZIP64
            if (compressedSize == ZIP64_MAGICVAL ||
                uncompressedSize == ZIP64_MAGICVAL ||
                localHeaderOffset == ZIP64_MAGICVAL
            ) {
                val fields = readZip64Fields(extra)
                var idx = 0
                if (uncompressedSize == ZIP64_MAGICVAL && idx < fields.size) {
                    uncompressedSize = fields[idx++]
                }
                if (compressedSize == ZIP64_MAGICVAL && idx < fields.size) {
                    compressedSize = fields[idx++]
                }
                if (localHeaderOffset == ZIP64_MAGICVAL && idx < fields.size) {
                    localHeaderOffset = fields[idx++]
                }
                // ADR-004 D2 兜底：extra 缺失或字段不足时，占位值 0xFFFFFFFF 会原样留下，
                // 于是「数据偏移」落到 4 GB 附近，读到的自然不是 local header，
                // 最终以「条目头损坏」这种把解析问题说成数据损坏的文案暴露。
                // 这里剔除该条目并把真实原因写进日志。
                if (compressedSize == ZIP64_MAGICVAL ||
                    uncompressedSize == ZIP64_MAGICVAL ||
                    localHeaderOffset == ZIP64_MAGICVAL
                ) {
                    Log.w(
                        TAG,
                        "parseCentralDirectory: skip '$name' — ZIP64 extra 未补齐 " +
                            "(slots=${fields.size}, extraLen=${extra.size})"
                    )
                    buf.position(recordEnd)
                    continue
                }
            }

            entries.add(
                ZipEntryInfo(
                    name = name,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    crc32 = crc32,
                    method = method,
                    localHeaderOffset = localHeaderOffset
                )
            )
            buf.position(recordEnd)
        }
        return entries
    }

    /**
     * ZIP64 extra field (0x0001)。字段按 uncompressed / compressed / localOffset / diskStart
     * 顺序排列，且**仅当对应 32 位字段被置为 0xFFFFFFFF 时才出现**，
     * 因此这里只收集全部 8 字节槽位，由调用方按需取值。
     */
    private fun readZip64Fields(extra: ByteArray): LongArray {
        val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) {
            val id = buf.short.toUInt().toInt()
            val size = buf.short.toUInt().toInt()
            if (size < 0 || size > buf.remaining()) break
            if (id == ZIP64_EXTRA_ID) {
                return LongArray(size / 8) { buf.long }
            }
            buf.position(buf.position() + size)
        }
        return LongArray(0)
    }

    /**
     * 读取条目内容。
     *
     * @param maxOutputBytes 产出上限，是防 zip bomb 的最后一道闸：
     *                       header 里的 uncompressedSize 可被伪造，不可只信它（ADR-001 D3）。
     */
    suspend fun readEntryBytes(
        source: ZipByteSource,
        entry: ZipEntryInfo,
        maxOutputBytes: Long,
        maxInputBytes: Long = Long.MAX_VALUE
    ): EntryData = withContext(Dispatchers.IO) {
        if (!entry.isSupported) throw IOException("UNSUPPORTED_ENTRY_METHOD")

        val dataOffset = readEntryDataOffset(source, entry)
        Log.i(TAG, "readEntryBytes: '${entry.name}' dataOffset=$dataOffset method=${entry.method}")

        if (entry.isStored) {
            val want = minOf(entry.uncompressedSize, maxOutputBytes).toInt()
            val out = ByteArray(want)
            val read = source.readAt(dataOffset, out)
            return@withContext EntryData(
                bytes = if (read == want) out else out.copyOf(read),
                producedBytes = read.toLong(),
                truncatedByLimit = entry.uncompressedSize > maxOutputBytes,
                complete = read.toLong() >= entry.uncompressedSize
            )
        }

        val inputWant = minOf(entry.compressedSize, maxInputBytes).toInt()
        val compressed = ByteArray(inputWant)
        val compressedRead = source.readAt(dataOffset, compressed)
        val actualInput =
            if (compressedRead == inputWant) compressed else compressed.copyOf(compressedRead)
        inflateRaw(actualInput, maxOutputBytes)
    }

    internal suspend fun readEntryDataOffset(
        source: ZipByteSource,
        entry: ZipEntryInfo
    ): Long {
        // 防御：ZIP64 占位没被解析掉的条目不该走到这里（parseCentralDirectory 已剔除），
        // 明确报解析问题，而不是让它变成含糊的「条目头损坏」。
        if (entry.localHeaderOffset == ZIP64_MAGICVAL) {
            Log.w(TAG, "readEntryDataOffset: '${entry.name}' 的 ZIP64 local header 偏移未解析")
            throw IOException("ZIP64_EXTRA_MISSING")
        }
        val available = source.size - entry.localHeaderOffset
        if (available <= 0) {
            Log.w(
                TAG,
                "readEntryDataOffset: '${entry.name}' lho=${entry.localHeaderOffset} " +
                    "越界 (source.size=${source.size})"
            )
            throw IOException("BAD_LOCAL_HEADER")
        }
        val readSize = minOf(LOCAL_HEADER_FIRST_READ_BYTES.toLong(), available).toInt()
        var header = ByteArray(readSize)
        var got = source.readAt(entry.localHeaderOffset, header)
        if (got < 30) {
            Log.w(
                TAG,
                "readEntryDataOffset: '${entry.name}' lho=${entry.localHeaderOffset} " +
                    "只读到 $got 字节（服务端返回空或被截断）"
            )
            throw IOException("BAD_LOCAL_HEADER")
        }

        var buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int.toLong() != LOCSIG) {
            Log.w(
                TAG,
                "readEntryDataOffset: '${entry.name}' lho=${entry.localHeaderOffset} " +
                    "首 4 字节=${header.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}" +
                    "（应为 504b0304）"
            )
            throw IOException("BAD_LOCAL_HEADER")
        }
        // fileNameLength 在绝对偏移 26、extraFieldLength 在 28（LOCSIG 已消费 4 字节）
        val nameLen = buf.getShort(26).toUInt().toInt()
        val extraLen = buf.getShort(28).toUInt().toInt()
        val dataOffset = 30 + nameLen + extraLen

        // ADR-004 D5：首读 1KB 未覆盖完整头部时，按实际长度补读一次。
        if (dataOffset > got) {
            if (dataOffset > available) {
                Log.w(TAG, "readEntryDataOffset: '${entry.name}' dataOffset=$dataOffset 超出可用 $available")
                throw IOException("BAD_LOCAL_HEADER")
            }
            header = ByteArray(dataOffset)
            got = source.readAt(entry.localHeaderOffset, header)
            if (got < dataOffset) {
                Log.w(TAG, "readEntryDataOffset: '${entry.name}' 补读 local header 只拿到 $got/$dataOffset")
                throw IOException("BAD_LOCAL_HEADER")
            }
            buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int.toLong() != LOCSIG) {
                Log.w(TAG, "readEntryDataOffset: '${entry.name}' 补读后首 4 字节仍非 LOCSIG")
                throw IOException("BAD_LOCAL_HEADER")
            }
        }

        return entry.localHeaderOffset + dataOffset
    }

    private fun inflateRaw(compressed: ByteArray, maxOutputBytes: Long): EntryData {
        // 技术债 6：zip 的 DEFLATE 是 raw deflate，没有 zlib 头，必须 nowrap
        val inflater = Inflater(true)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var produced = 0L
        var hitOutputLimit = false
        // 必须在 finally 的 end() 之前取状态：end() 之后再调 finished() 结果不可靠
        var complete = false

        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                val room = maxOutputBytes - produced
                if (room <= 0) {
                    hitOutputLimit = true
                    break
                }
                val toWrite = min(n.toLong(), room).toInt()
                out.write(buf, 0, toWrite)
                produced += toWrite
            }
            complete = inflater.finished()
        } catch (e: DataFormatException) {
            throw IOException("BAD_DEFLATE_DATA")
        } finally {
            inflater.end()
        }

        return EntryData(
            bytes = out.toByteArray(),
            producedBytes = produced,
            truncatedByLimit = hitOutputLimit,
            complete = complete
        )
    }

    /**
     * ADR-001 D6：NUL 字节硬拦截 + 可打印字符比例。
     *
     * 只看比例是不够的——`apex_info.pb`、`care_map.pb` 这类二进制 protobuf
     * 含大量可打印字节却没有 NUL，按比例会被误判成文本并渲染出一屏乱码。
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = bytes.copyOf(min(bytes.size, 4096))
        var printable = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v == 9 || v == 10 || v == 13 || v in 32..126 || v >= 0x80) printable++
        }
        return printable.toDouble() / sample.size >= 0.9
    }

    /** ADR-001 D7：BOM → 严格 UTF-8 → GBK 回退。返回 (文本, 编码名)。 */
    fun decodeText(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 BOM"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE BOM"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE BOM"
        }
        // 截断预览可能正好切在多字节字符中间，回退 1~3 字节再验一次
        for (trim in 0..3) {
            if (bytes.size - trim <= 0) break
            val slice = bytes.copyOf(bytes.size - trim)
            if (isStrictUtf8(slice)) return String(slice, Charsets.UTF_8) to "UTF-8"
        }
        return runCatching { String(bytes, Charset.forName("GBK")) to "GBK" }
            .getOrDefault(String(bytes, Charsets.ISO_8859_1) to "ISO-8859-1")
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)
    }

    fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    // ================================================================
    // 签名块（META-INF/CERT.RSA 等）解析
    // ================================================================

    /**
     * 把 PKCS#7 签名块转成可读的证书信息文本。
     *
     * CERT.RSA 是 DER 编码的 PKCS#7 SignedData，二进制无法直接预览。
     * 这里定位其中的 X.509 证书并交给系统 CertificateFactory 解析，
     * 输出主题/签发者/有效期/公钥/签名算法/指纹。
     *
     * 定位算法（已用 openssl 生成的真实样本离线验证）：
     * 证书普遍 >256 字节，故只匹配 `30 82 XX XX` 形式的 SEQUENCE，
     * 再校验其内部结构为 tbsCertificate / signatureAlgorithm / signatureValue 三段。
     * 纯文本、全零、随机数据均已验证零误报。
     */
    fun decodeSignatureBlock(der: ByteArray): String? {
        val certs = parseCertificates(der)
        if (certs.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("PKCS#7 签名块，共 ${certs.size} 个证书")
        certs.forEachIndexed { index, cert ->
            sb.appendLine()
            if (certs.size > 1) sb.appendLine("[$index]")
            sb.append(cert)
        }
        return sb.toString().trimEnd()
    }

    /** DER 中的一个 TLV。 */
    private data class Tlv(val tag: Int, val contentStart: Int, val length: Int) {
        val next: Int get() = contentStart + length
    }

    private fun readTlv(buf: ByteArray, pos: Int): Tlv? {
        if (pos + 2 > buf.size) return null
        val tag = buf[pos].toInt() and 0xFF
        val first = buf[pos + 1].toInt() and 0xFF
        var p = pos + 2
        val length: Int
        if (first and 0x80 != 0) {
            val n = first and 0x7F
            if (n > 4 || p + n > buf.size) return null
            var v = 0L
            for (i in 0 until n) v = (v shl 8) or (buf[p + i].toLong() and 0xFF)
            p += n
            if (v > Int.MAX_VALUE) return null
            length = v.toInt()
        } else {
            length = first
        }
        if (p + length > buf.size) return null
        return Tlv(tag, p, length)
    }

    /** 扫描缓冲区，返回每个证书的「起始位置 → 描述文本」。 */
    private fun parseCertificates(buf: ByteArray): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i + 4 <= buf.size) {
            // 只匹配 30 82 XX XX（2 字节长长度编码的 SEQUENCE）
            if ((buf[i].toInt() and 0xFF) != 0x30 || (buf[i + 1].toInt() and 0xFF) != 0x82) {
                i++
                continue
            }
            val outer = readTlv(buf, i) ?: run { i++; continue }
            if (outer.tag != 0x30 || outer.length < 200) { i++; continue }

            // 内部须为 SEQUENCE(tbs) SEQUENCE(algid) BIT STRING(sig)
            val tbs = readTlv(buf, outer.contentStart)
            if (tbs == null || tbs.tag != 0x30) { i++; continue }
            val algid = readTlv(buf, tbs.next)
            if (algid == null || algid.tag != 0x30) { i++; continue }
            val sig = readTlv(buf, algid.next)
            if (sig == null || sig.tag != 0x03) { i++; continue }

            val headerLen = outer.contentStart - i
            val certDer = buf.copyOfRange(i, i + headerLen + outer.length)
            val desc = runCatching { describeCertificate(certDer) }.getOrNull()
            if (desc != null) result.add(desc)
            i = outer.next // 跳过整张证书，避免把内部结构当成新证书
        }
        return result
    }

    private fun describeCertificate(certDer: ByteArray): String? {
        val factory = CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(certDer.inputStream()) as? X509Certificate
            ?: return null
        val sb = StringBuilder()
        sb.appendLine("主题：${cert.subjectX500Principal.name}")
        sb.appendLine("签发者：${cert.issuerX500Principal.name}")
        sb.appendLine("序列号：${cert.serialNumber.toString(16).uppercase()}")
        sb.appendLine("有效期：${cert.notBefore} ~ ${cert.notAfter}")
        sb.appendLine("签名算法：${cert.sigAlgName}")
        sb.appendLine("公钥算法：${cert.publicKey.algorithm} ${keySize(cert.publicKey)}")
        sb.appendLine("SHA-256：${sha256Hex(cert.encoded)}")
        val expired = runCatching { cert.checkValidity() }.isFailure
        if (expired) sb.appendLine("状态：已过期或尚未生效")
        return sb.toString().trimEnd()
    }

    private fun keySize(key: java.security.PublicKey): String {
        return runCatching {
            when (key) {
                is java.security.interfaces.RSAPublicKey -> "(${key.modulus.bitLength()} 位)"
                is java.security.interfaces.ECPublicKey ->
                    "(${key.params.curve.field.fieldSize} 位)"
                is java.security.interfaces.DSAPublicKey -> "(${key.params.p.bitLength()} 位)"
                else -> ""
            }
        }.getOrDefault("")
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02X".format(it) }
            .chunked(2).joinToString(":")
    }
}

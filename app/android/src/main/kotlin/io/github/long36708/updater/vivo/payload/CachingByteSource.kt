package io.github.long36708.updater.vivo.payload

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 在任意 [ZipByteSource] 外套一层块级 LRU 缓存（ADR-004 D6）。
 *
 * 动机：一次「查看分区列表」会反复读同几个小区间——尾部 EOCD、central directory、
 * payload.bin 的 local header、CrAU 头。而 ZIP 浏览（`listZipEntries`）也要再读一遍
 * 尾部与 central directory。没有缓存时这些区间会被重复请求。
 *
 * 取舍：
 * - 块大小 1MB、最多 4 块（≈4MB 常驻），是手机端内存与命中率的折中；
 *   参考实现 VioletToolBox 在桌面用 8 块。
 * - 单次请求超过 [bypassThresholdBytes] 时直接穿透——大块顺序读（manifest、
 *   条目提取）命中率低，进缓存只会把有用的小区间挤出去。
 * - 缓存键是绝对块号，不依赖 HTTP 会话；换包时必须整体重建（由上层 `resetZipBrowser` 负责）。
 */
class CachingByteSource(
    private val base: ZipByteSource,
    private val blockSize: Int = 1 shl 20,
    private val maxBlocks: Int = 4,
    private val bypassThresholdBytes: Int = 4 shl 20,
) : ZipByteSource {

    override val size: Long get() = base.size

    /** 保护 [blocks] 与 [base] 的读取；本类只做串行读，无并发收益但有并发安全。 */
    private val mutex = Mutex()

    /** 插入顺序即 LRU 顺序：命中时 remove + put 续期。 */
    private val blocks = LinkedHashMap<Long, ByteArray>()

    override suspend fun readAt(offset: Long, out: ByteArray): Int {
        if (out.isEmpty()) return 0

        val total = size
        if (offset < 0 || offset >= total) return 0

        val requested = minOf(out.size.toLong(), total - offset).toInt()
        if (requested <= 0) return 0

        if (requested > bypassThresholdBytes) {
            if (requested == out.size) return base.readAt(offset, out)
            val tmp = ByteArray(requested)
            val read = base.readAt(offset, tmp)
            System.arraycopy(tmp, 0, out, 0, read)
            return read
        }

        var copied = 0
        var position = offset
        while (copied < requested) {
            val index = position / blockSize
            val block = blockAt(index)
            val blockStart = index * blockSize
            val sourceOffset = (position - blockStart).toInt()
            if (sourceOffset >= block.size) break

            val length = minOf(block.size - sourceOffset, requested - copied)
            System.arraycopy(block, sourceOffset, out, copied, length)
            copied += length
            position += length
        }
        return copied
    }

    private suspend fun blockAt(index: Long): ByteArray = mutex.withLock {
        blocks.remove(index)?.let { cached ->
            blocks[index] = cached // 命中即续期
            return@withLock cached
        }

        val start = index * blockSize
        val length = minOf(blockSize.toLong(), size - start).toInt()
        val buffer = ByteArray(length)
        val read = base.readAt(start, buffer)
        val block = if (read == length) buffer else buffer.copyOf(read)

        blocks[index] = block
        while (blocks.size > maxBlocks) {
            val oldest = blocks.keys.firstOrNull() ?: break
            blocks.remove(oldest)
        }
        block
    }
}

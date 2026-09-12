package io.github.long36708.updater.vivo.payload

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * 不可重试的协议/能力问题（ADR-004 D4）。
 *
 * message 本身就是给 UI 的标记文案（`VivoPayloadViewModel.mapErrorMessage` 精确匹配），
 * 因此这类异常必须原样透传，不能被包装或重试。
 */
private class RangeMarkerException(message: String) : IOException(message)

/** 可重试的失败：限流 / 服务端故障 / 网络中断（ADR-004 D7）。 */
private class RetryableHttpException(
    message: String,
    val retryAfterMs: Long? = null,
) : IOException(message)

object VivoPayloadHttpUtil {

    private const val MAX_ATTEMPTS = 3
    private const val BASE_RETRY_DELAY_MS = 450L
    private const val MAX_RETRY_DELAY_MS = 4_000L

    private lateinit var url: String
    private lateinit var fileName: String
    private var fileLength: Long = 0
    private var position: Long = 0
    /**
     * 每次 [init] 递增的会话号。
     *
     * 单例只有一个 url/fileLength，但 [ZipByteSource] 可能还持有上一次的条目数据。
     * 会话号让这些过期的 source 能发现自己已失效，而不是把旧偏移读成新包的数据
     * （表现为莫名的「条目头损坏」）。
     */
    private var session: Long = 0

    /** 本次会话（自最近一次 [init] 起）实际从网络读取的字节数（ADR-004 D9）。 */
    private var sessionBytes: Long = 0
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    suspend fun init(link: String) = withContext(Dispatchers.IO) {
        url = link
        position = 0
        sessionBytes = 0
        session++
        runWithRetry("init") {
            val request = Request.Builder()
                .url(link)
                .addHeader("Range", "bytes=0-0")
                .build()

            client.newCall(request).execute().use { response ->
                // ADR-004 D4：只认 206。放过 200 会把「整包的第一段」当成请求区间，
                // 上层无从察觉，最终表现为莫名其妙的字段错乱。
                if (response.code != 206) {
                    Log.w(
                        "VivoPayload",
                        "http init: code=${response.code} Content-Range=${response.header("Content-Range")}"
                    )
                    throw classifyFailure(response.code, response.header("Retry-After"))
                }
                // 总长只能取自 Content-Range（206 下 Content-Length 是片段长度）
                val contentRange = response.header("Content-Range")
                fileLength = contentRange?.substringAfter('/')?.trim()?.toLongOrNull() ?: 0L
                if (fileLength <= 0) {
                    Log.w("VivoPayload", "http init: Content-Range 缺失或非法: $contentRange")
                    throw RangeMarkerException("RANGE_NOT_SUPPORTED")
                }
                fileName = getFileNameFromHeaders(link, response.header("Content-Disposition"))
                Log.i("VivoPayload", "http init OK: Content-Range=$contentRange, fileLength=$fileLength, fileName=$fileName")
            }
        }
    }

    fun length(): Long = fileLength

    /** 本次会话累计从网络读取的字节数（ADR-004 D9）。 */
    fun bytesRead(): Long = sessionBytes

    fun sessionId(): Long = session

    fun position(): Long = position

    fun getFileName(): String = fileName

    suspend fun readSync(byteArray: ByteArray): Int = withContext(Dispatchers.IO) {
        var currentPosition = position
        var totalBytesRead = 0

        // 严格读满 byteArray：服务端可能对 Range 请求分片/截断返回，
        // 单次请求读不满时继续发后续 Range 请求补足，避免上层 Payload.parseFrom
        // 拿到半截数据后无限循环解析导致 UI 一直转圈。
        var reqCount = 0
        while (totalBytesRead < byteArray.size) {
            reqCount++
            val remaining = byteArray.size - totalBytesRead
            val rangeStart = currentPosition
            val rangeHeader = "bytes=$rangeStart-${rangeStart + remaining - 1}"
            if (reqCount <= 3 || totalBytesRead == 0) {
                Log.d("VivoPayload", "readSync req#$reqCount range=$rangeHeader want=$remaining")
            }

            // ADR-004 D7：整次 Range 请求（含响应体读取）都在重试范围内。
            // 重试时从 rangeStart 重发并覆写同一段缓冲，因此是幂等的。
            val copied = runWithRetry("readSync#$reqCount") {
                var written = 0
                val request = Request.Builder().url(url).addHeader("Range", rangeHeader).build()

                client.newCall(request).execute().use { response ->
                    // ADR-004 D4：只认 206。200 表示服务端忽略了 Range，
                    // 响应体是文件开头的数据，静默接受会让上层读到错位内容而不自知。
                    if (response.code != 206) {
                        Log.w(
                            "VivoPayload",
                            "readSync: range=$rangeHeader code=${response.code} " +
                                "Content-Range=${response.header("Content-Range")}"
                        )
                        throw classifyFailure(response.code, response.header("Retry-After"))
                    }
                    // 起点必须与请求一致，否则 Range 拼接会整体错位
                    val contentRange = response.header("Content-Range")
                    val start = contentRange
                        ?.substringAfter(' ', "")
                        ?.substringBefore('-')
                        ?.toLongOrNull()
                    if (start != rangeStart) {
                        Log.w(
                            "VivoPayload",
                            "readSync: 区间不符 want=$rangeStart got=$contentRange"
                        )
                        throw RangeMarkerException("RANGE_MISMATCH")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val inputStream = body.byteStream()
                    val buffer = ByteArray(4 * 1024)
                    // 单次请求内按 4K 缓冲读，读满剩余量或 stream 结束为止
                    while (written < remaining) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        val toCopy = minOf(bytesRead, remaining - written)
                        System.arraycopy(buffer, 0, byteArray, totalBytesRead + written, toCopy)
                        written += toCopy
                    }
                }
                written
            }

            // 本次 Range 请求没读到任何字节：说明已到文件末尾或连接被截断，
            // 不再无限补请求，直接返回已读量（上层会据此判定数据不足）。
            if (copied <= 0) break
            totalBytesRead += copied
            currentPosition += copied
        }
        sessionBytes += totalBytesRead
        if (totalBytesRead < byteArray.size) {
            Log.w("VivoPayload", "readSync EOF: read $totalBytesRead / ${byteArray.size} bytes (data truncated)")
        }
        position = currentPosition
        totalBytesRead
    }

    /**
     * 把一次 HTTP 失败分类（ADR-004 D7）。
     *
     * 关键：429/5xx 是可重试的临时故障，**不能**报成「服务器不支持分段读取」——
     * 那会让用户放弃一个本来可用的直链。
     */
    private fun classifyFailure(code: Int, retryAfterHeader: String?): IOException = when {
        code == 416 -> RangeMarkerException("RANGE_INVALID_OFFSET")
        code == 429 -> RetryableHttpException("HTTP_429", parseRetryAfterMs(retryAfterHeader))
        code in 500..599 -> RetryableHttpException("HTTP_$code")
        // 其余（含 200：Range 被忽略）都属「服务器拒绝按区间提供数据」
        else -> RangeMarkerException("RANGE_NOT_SUPPORTED")
    }

    /** `Retry-After` 只解析秒数形式；HTTP-date 形式走退避即可。 */
    private fun parseRetryAfterMs(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(0L, MAX_RETRY_DELAY_MS)
    }

    private fun backoffMs(attempt: Int): Long {
        val factor = 1L shl (attempt - 1).coerceIn(0, 4)
        return (BASE_RETRY_DELAY_MS * factor).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    /**
     * 最多重试 [MAX_ATTEMPTS] 次（ADR-004 D7）。
     *
     * - [RangeMarkerException]：能力/协议问题，重试无意义，直接抛出（保留标记文案）；
     * - [RetryableHttpException]：限流/服务端故障，按 `Retry-After` 或指数退避后重试；
     * - 其它 [IOException]：网络层问题（超时、连接重置），同样退避重试。
     *
     * 重试耗尽后统一收敛为 `NETWORK_ERROR`，用于告诉用户「是网络问题，不是包坏了」。
     */
    private suspend fun <T> runWithRetry(tag: String, block: () -> T): T {
        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return block()
            } catch (e: RangeMarkerException) {
                throw e
            } catch (e: RetryableHttpException) {
                lastError = e
                if (attempt >= MAX_ATTEMPTS) break
                val waitMs = e.retryAfterMs ?: backoffMs(attempt)
                Log.w("VivoPayload", "$tag: 第 $attempt/$MAX_ATTEMPTS 次失败(${e.message})，${waitMs}ms 后重试")
                delay(waitMs)
            } catch (e: IOException) {
                lastError = e
                if (attempt >= MAX_ATTEMPTS) break
                val waitMs = backoffMs(attempt)
                Log.w("VivoPayload", "$tag: 第 $attempt/$MAX_ATTEMPTS 次网络失败(${e.message})，${waitMs}ms 后重试")
                delay(waitMs)
            }
        }
        Log.e("VivoPayload", "$tag: 重试 $MAX_ATTEMPTS 次后仍失败: ${lastError?.message}")
        throw IOException("NETWORK_ERROR", lastError)
    }

    fun seek(bytePosition: Long) {
        if (bytePosition in 0 until fileLength) {
            position = bytePosition
        } else {
            throw IllegalArgumentException("Invalid seek position")
        }
    }

    private fun getFileNameFromHeaders(url: String, contentDisposition: String?): String {
        if (!contentDisposition.isNullOrEmpty()) {
            val dispositionParts = contentDisposition.split(";")
            for (part in dispositionParts) {
                if (part.trim().startsWith("filename=")) {
                    return part.trim().substringAfter("=").replace("\"", "")
                }
            }
        }
        return Paths.get(java.net.URI(url).path).fileName.toString()
    }
}

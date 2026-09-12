package io.github.long36708.updater.vivo.payload

import android.util.Log
import chromeos_update_engine.UpdateMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

object PayloadUtil {

    private const val MAGIC_VALUE = "CrAU"
    private const val FORMAT_VERSION = 2L

    private val mutex by lazy { Mutex() }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * 严格读满 [size] 字节，读不到即视为数据截断（ADR-004）。
     * 半截 manifest 会让 protobuf 解析进入不可预期状态，宁可明确报错。
     */
    private suspend fun readFully(source: ZipByteSource, offset: Long, size: Int): ByteArray {
        if (size < 0) throw IOException("Invalid read size")
        val out = ByteArray(size)
        var got = 0
        while (got < size) {
            val chunk = out.copyOfRange(got, size)
            val read = source.readAt(offset + got, chunk)
            if (read <= 0) break
            System.arraycopy(chunk, 0, out, got, read)
            got += read
        }
        if (got < size) throw IOException("TRUNCATED_READ")
        return out
    }

    /**
     * 从 zip 源解析 payload.bin（ADR-004 D1/D2/D3/D8）。
     *
     * zip 结构解析全部委托 [VivoZipBrowser]（含 ZIP64 extra、扫描式 CENSIG），
     * 这里只负责「payload.bin 是 STORED」这一前提校验与 CrAU/manifest 解析。
     */
    suspend fun initPayload(fileName: String, source: ZipByteSource): Payload {
        val entries = VivoZipBrowser.listZipEntries(source)
        val entry = VivoZipBrowser.findPayloadEntry(entries)
            ?: throw IOException("NOT_A_PAYLOAD_ZIP")
        // D8：远程无法随机解压，非 STORED 必须明确报错，而不是让后面撞上 Invalid magic value
        if (!entry.isStored) throw IOException("PAYLOAD_NOT_STORED")

        val payloadDataOffset = VivoZipBrowser.readEntryDataOffset(source, entry)
        Log.i(
            "VivoPayload",
            "initPayload: entry='${entry.name}' offset=$payloadDataOffset " +
                "size=${entry.uncompressedSize} method=${entry.method} total=${source.size}"
        )

        val payloadSource = SubrangeByteSource(source, payloadDataOffset, entry.uncompressedSize)

        val header = readFully(payloadSource, 0, 24)
        if (String(header, 0, 4, StandardCharsets.UTF_8) != MAGIC_VALUE) {
            throw IOException("Invalid magic value")
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val fileFormatVersion = buf.long
        if (fileFormatVersion != FORMAT_VERSION) {
            throw IOException("Unsupported file format version")
        }
        val manifestSize = buf.long
        val metadataSignatureSize = buf.int

        if (manifestSize <= 0 || manifestSize > payloadSource.size) {
            throw IOException("Invalid manifest size: $manifestSize")
        }
        if (metadataSignatureSize < 0 || metadataSignatureSize > payloadSource.size) {
            throw IOException("Invalid metadata signature size: $metadataSignatureSize")
        }

        val manifest = readFully(payloadSource, 24, manifestSize.toInt())
        if (metadataSignatureSize > 0) {
            readFully(payloadSource, 24 + manifestSize, metadataSignatureSize)
        }

        val deltaArchiveManifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifest)
        val payloadHeader = PayloadHeader(fileFormatVersion, manifestSize, metadataSignatureSize)
        // 分区提取仍按「绝对文件偏移」seek（extractFromHttp 用 payload.dataOffset + op.dataOffset），
        // 因此这里要把 payload.bin 在 zip 中的绝对起点加回去。
        val dataOffset = payloadDataOffset + 24 + manifestSize + metadataSignatureSize
        return Payload(
            fileName,
            payloadHeader,
            deltaArchiveManifest,
            dataOffset,
            deltaArchiveManifest.blockSize,
            source.size,
            false
        )
    }

    fun getPartitionInfoList(payload: Payload): List<PartitionInfo> {
        val blockSize = payload.deltaArchiveManifest.blockSize
        val srcCount = payload.deltaArchiveManifest.partitionsList.size
        Log.i("VivoPayload", "getPartitionInfoList: input partitions=$srcCount, blockSize=$blockSize")
        return payload.deltaArchiveManifest.partitionsList.map { partition ->
            // 真实镜像大小：所有 dst extents 占用的块数 * blockSize。
            // 某些分区（纯 ZERO / 无数据）operationsList 或 dstExtentsList 可能为空，需做保护。
            val rawSize = partition.operationsList
                .flatMap { it.dstExtentsList }
                .mapNotNull { extent -> extent?.let { it.startBlock + it.numBlocks } }
                .maxOrNull()
                ?.let { (it * blockSize).toLong() }
                ?: partition.newPartitionInfo.size

            val typeStats = partition.operationsList
                .groupingBy { it.type.name }
                .eachCount()

            PartitionInfo(
                partitionName = partition.partitionName,
                size = partition.newPartitionInfo.size,
                rawSize = rawSize,
                sha256 = partition.newPartitionInfo.hash.toByteArray().toHexString(),
                operationsCount = partition.operationsList.size,
                mergeOperationsCount = partition.mergeOperationsList.size,
                typeStats = typeStats
            )
        }
    }

    private suspend fun extractFromHttp(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        httpUtil: VivoPayloadHttpUtil,
        blockSize: Int,
        offset: Long,
    ) {
        mutex.withLock {
            httpUtil.seek(offset + op.dataOffset)
            withContext(Dispatchers.IO) {
                partOutput.seek(op.dstExtentsList[0].startBlock * blockSize)
            }

            val copyCompressedData: (CompressorInputStream) -> Unit = { compressorInputStream ->
                compressorInputStream.use { input ->
                    FileOutputStream(partOutput.fd).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            when (op.type) {
                UpdateMetadata.InstallOperation.Type.REPLACE_XZ -> {
                    val data = ByteArray(op.dataLength.toInt())
                    httpUtil.readSync(data)
                    copyCompressedData(XZCompressorInputStream(data.inputStream().buffered()))
                }
                UpdateMetadata.InstallOperation.Type.REPLACE_BZ -> {
                    val data = ByteArray(op.dataLength.toInt())
                    httpUtil.readSync(data)
                    copyCompressedData(BZip2CompressorInputStream(BufferedInputStream(data.inputStream().buffered())))
                }
                UpdateMetadata.InstallOperation.Type.REPLACE -> {
                    val data = ByteArray(op.dataLength.toInt())
                    httpUtil.readSync(data)
                    withContext(Dispatchers.IO) {
                        partOutput.write(data)
                    }
                }
                UpdateMetadata.InstallOperation.Type.ZERO -> {
                    val data = ByteArray(op.dataLength.toInt()) { 0x00 }
                    withContext(Dispatchers.IO) {
                        partOutput.write(data)
                    }
                }
                else -> throw RuntimeException("Unsupported operation type ${op.type}")
            }
        }
    }

    suspend fun extractPartition(
        metadataPartition: UpdateMetadata.PartitionUpdate,
        input: Any,
        outputDir: String,
        payload: Payload,
        onProgressUpdate: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val downloadDir = Paths.get(outputDir)
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir)
            }

            RandomAccessFile(
                "$outputDir/${metadataPartition.partitionName}.img",
                "rw"
            ).use { partOutput ->
                val httpUtil = input as? VivoPayloadHttpUtil
                    ?: throw IllegalArgumentException("Only online URL is supported")
                metadataPartition.operationsList.forEach { operation ->
                    extractFromHttp(
                        operation,
                        partOutput,
                        httpUtil,
                        payload.blockSize,
                        payload.dataOffset
                    )
                    onProgressUpdate(partOutput.channel.position())
                }
            }
        }
    }

}

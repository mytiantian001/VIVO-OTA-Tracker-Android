package io.github.long36708.updater.vivo

import android.content.Context
import android.util.Log
import io.github.long36708.updater.crypto.VivoCrypto
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

class VivoOtaClient(private val context: Context) {

    companion object {
        private const val TAG = "VivoOtaClient"
        private const val TOKEN_NATIVE = "jnisgmain_v2@com.bbk.updater"
        /** redirPost 未返回地址时的兜底下载地址前缀（后接 pkName）。 */
        private const val PK_BASE_URL = "https://sysupdxdl.vivo.com.cn/upgrade/oem/files/"

        /**
         * h5Url 页面地址 → 日志数据文件地址：H5 页面内容由 JS 从 data/CN.js 加载，
         * 解析日志正文时直接抓该文件而非渲染后的 HTML。
         */
        fun changelogDataUrl(h5Url: String): String =
            h5Url.replace(Regex("/index\\.html$"), "/data/CN.js")

        /** changelogDataUrl 的逆变换：还原出可在浏览器直接打开的 H5 页面地址。 */
        fun changelogPageUrl(url: String): String =
            url.replace(Regex("/data/[^/]+\\.js$"), "/index.html")
    }

    /** 可选择的升级服务器域名（参照升级检查接口协议分析.md）。 */
    enum class Domain(val value: String, val host: String) {
        /** 国行（默认）：sysupgrade.vivo.com.cn */
        CN("CN", "sysupgrade.vivo.com.cn"),
        /** 出口版：asia-sysupgrade-api.vivoglobal.com（KZ/RU 等海外区域使用） */
        GLOBAL("GLOBAL", "asia-sysupgrade-api.vivoglobal.com");

        /** 根据接口路径构造完整 URL：国行路径前缀为 /，出口版前缀为 /api。 */
        fun url(path: String): String = "https://$host${if (this == GLOBAL) "/api$path" else path}"
    }

    enum class QueryChannel(val value: String) {
        NORMAL("NORMAL"),
        TRIAL("TRIAL"),
        BETA("BETA"),
        ALPHA("ALPHA")
    }

    fun initCrypto(): Boolean {
        VivoCrypto.clearServerUrl(context)
        return VivoCrypto.init(context)
    }

    fun query(
        codename: String,
        modelSwVer: String,
        swVersion: String,
        androidVersion: Int,
        isPhone: Boolean,
        isFull: Boolean,
        sn: String = "A0000000000000A",
        imei: String = "",
        channel: QueryChannel = QueryChannel.NORMAL,
        domain: Domain = Domain.CN
    ): VivoOtaResult {
        val hwVer = codename + "MA"
        val fullSwVersion = if (swVersion.contains(".W")) "$swVersion.V000L1" else swVersion
        val fullVer = if (swVersion.contains(".W")) "${codename}_A_$swVersion.V000L1" else "${codename}_A_$swVersion"
        val versionLong = if (swVersion.contains(".W")) "${codename}_N_${codename}MA_$swVersion.V000L1" else "${codename}_N_${codename}MA_$swVersion"

        val ts = SimpleDateFormat("yy_MM_dd-HH_mm_ss").format(Date())
        val random = Random()
        val elapsedtime = if (isPhone) 140000 + random.nextInt(80000) else 2000000 + random.nextInt(500000)
        val isFullInt = if (isFull) 1 else 0
        // imei 由调用方给出（本机/手输/随机）；为空时退化到客户端自身的解析逻辑
        val effectiveImei = if (imei.isBlank()) genImei() else VivoImei.sanitize(imei).ifEmpty { genImei() }

        val p = LinkedHashMap<String, Any>()
        p["vgcNewActiveVer"] = ""
        p["nt"] = "WIFI"
        p["vgcSwVer"] = "1.1.1"
        p["fullVer"] = fullVer
        p["emmcid"] = ""
        p["sm1"] = "null"
        p["sm2"] = "null"
        p["model"] = codename
        p["hasVgc"] = 1
        p["vgcNewPassiveVer"] = ""
        p["ch"] = "N"
        p["gn"] = 0
        p["newActiveVer"] = ""
        p["version"] = versionLong
        p["st2"] = 0
        p["cu"] = "N"
        p["srm2"] = 0
        p["srm1"] = 0
        p["cy"] = "CN-ZH"
        p["sn2"] = "null"
        p["ne"] = "null"
        p["sn1"] = "null"
        p["public_model"] = modelSwVer
        p["newPassiveVer"] = ""
        p["hwVer"] = hwVer
        p["swVer"] = fullSwVersion
        p["language"] = "zh_CN"
        p["isMan"] = 1
        p["isFull"] = isFullInt
        p["protocalversion"] = "1.0"
        p["checkTrige"] = "MANUL"
        p["isstlifeover"] = "false"
        p["hwFingerprint"] = ""

        if (isPhone) {
            p["vgcCu"] = "V000"
            p["sf"] = 1
            p["si"] = "null"
            p["dType"] = "phone"
            p["s_n"] = "null"
            p["elapsedtime"] = elapsedtime
            p["st1"] = 100000 + random.nextInt(60000)
            p["imei"] = effectiveImei
            p["ms"] = 0
            p["mtype"] = "no"
            p["radiotype"] = "L"
        } else {
            p["romVersion"] = "Funtouch $androidVersion.0"
            p["occurTime"] = ts
            p["vgcCu"] = "NULL"
            p["battery"] = 69
            p["sf"] = 0
            p["si"] = ""
            p["oem"] = "${codename}_CN-ZH_FULL_SC_NULL"
            p["dType"] = "tablet"
            p["oemProjects"] = "$codename+${codename}B"
            p["verName"] = "1.1.1.1"
            p["elapsedtime"] = elapsedtime
            p["verCode"] = "000000001"
            p["st1"] = 0
            p["snp"] = sn
            p["imei"] = ""
            p["sdkVersion"] = 34
            p["isCharge"] = "false"
            p["ms"] = -1
            p["mtype"] = "FULL_SC"
            p["radiotype"] = "A"
        }

        // 尝鲜/公测/内测通道：去掉正式版专属字段，改用 taste 字段集
        val isTaste = channel != QueryChannel.NORMAL
        if (isTaste) {
            p.remove("isMan")
            p.remove("protocalversion")
            p.remove("checkTrige")
            p.remove("isstlifeover")
            p["trigger"] = "verfy"
            p["isSupportVgcTaste"] = 1
            p["isSupportShowNote"] = 1
            p["hwFingerprint"] = ""
        }

        val rawParams = joinParams(p)
        Log.d(TAG, "Request params (channel=${channel.value}): $rawParams")

        // 公测/内测先查询报名状态（beta/query 或 alpha/getAlphaState）
        if (channel == QueryChannel.BETA || channel == QueryChannel.ALPHA) {
            val betaParams = buildBetaBaseParams(
                model = codename,
                hwVer = hwVer,
                swVer = swVersion,
                cy = "CN-ZH",
                cu = "N",
                dType = if (isPhone) "phone" else "tablet",
                vgcCu = "V000",
                imei = effectiveImei,
                snp = sn,
                isPhone = isPhone,
                romVer = fullSwVersion
            ) + if (channel == QueryChannel.BETA) "&manual=1&push=0" else "&manual=1"
            val stateJson = if (channel == QueryChannel.BETA) {
                sendBetaRequest(betaParams, domain)
            } else {
                sendAlphaRequest(betaParams, domain)
            }
            Log.d(TAG, "${channel.value} state: $stateJson")
        }

        val updateResponse = if (isTaste) sendTasteRequest(rawParams, domain) else sendFreshEncryptedRequest(rawParams, domain)
        if (updateResponse.startsWith("[Error]")) {
            throw RuntimeException(updateResponse)
        }

        return parseResult(updateResponse, isPhone, modelSwVer, codename, swVersion, channel, domain)
    }

    /** 公测/内测共用参数集，与 PC 版 buildBetaBaseParams 对应。 */
    private fun buildBetaBaseParams(
        model: String, hwVer: String, swVer: String,
        cy: String, cu: String, dType: String, vgcCu: String,
        imei: String, snp: String, isPhone: Boolean, romVer: String
    ): String {
        val sb = StringBuilder()
        sb.append("model=").append(model)
        sb.append("&hwVer=").append(hwVer)
        sb.append("&swVer=").append(swVer)
        sb.append("&cy=").append(cy)
        sb.append("&cu=").append(cu)
        sb.append("&dType=").append(dType)
        sb.append("&vgcCu=").append(vgcCu)
        sb.append(if (isPhone) "&imei=$imei" else "&snp=$snp")
        sb.append("&romVer=").append(romVer)
        return sb.toString()
    }

    private fun parseResult(
        updateResponse: String,
        isPhone: Boolean,
        modelSwVer: String,
        codename: String,
        swVersion: String,
        channel: QueryChannel = QueryChannel.NORMAL,
        domain: Domain = Domain.CN
    ): VivoOtaResult {
        Log.d(TAG, "Raw OTA response: $updateResponse")

        val updateVersion = extractJsonStr(updateResponse, "version\":\"")
        val pkName = extractJsonStr(updateResponse, "pkName\":\"")
        val pkLen = extractJsonStr(updateResponse, "pkLen\":\"")
        val sizeMb = try { (pkLen.toLong() / 1048576).toString() } catch (_: Exception) { "" }

        var downloadUrl = ""

        val changelogUrl = extractJsonStr(updateResponse, "h5Url\":\"").let {
            if (it == "(Not found)") "" else it.replace("\\/", "/").let(::changelogDataUrl)
        }
        Log.d(TAG, "Changelog URL: '$changelogUrl'")

        val securityPatch = extractField(updateResponse, listOf(
            "securityPatch", "securityPath", "spVersion", "secPatch", "securityVersion", "secPatchDate", "security_patch"
        ))
        val updateDate = extractField(updateResponse, listOf(
            "createTime", "updateTime", "releaseTime", "publishTime", "upgradeTime", "pubdate", "submitTime"
        ))
        val md5 = extractField(updateResponse, listOf("md5", "fileMd5", "pkMd5"))

        Log.d(TAG, "Security patch: '$securityPatch', Update date: '$updateDate', MD5: '$md5'")

        val pkUrl = extractPkUrl(updateResponse)
        if (pkUrl != null) {
            try {
                val queryStart = pkUrl.indexOf("?")
                val redirParams = if (queryStart >= 0) pkUrl.substring(queryStart + 1) else pkUrl
                val redirRes = requestRedirPost(redirParams, domain)
                Log.d(TAG, "Redir response: $redirRes")
                val dataIdx = redirRes.indexOf("\"data\":\"")
                if (dataIdx >= 0) {
                    val urlStart = dataIdx + 8
                    val urlEnd = redirRes.indexOf("\"", urlStart)
                    if (urlEnd > urlStart) {
                        downloadUrl = redirRes.substring(urlStart, urlEnd).replace("\\/", "/")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "redirPost failed: ${e.message}")
            }
        }

        // redirPost 未给出地址（无 pk / 请求失败 / data 缺失）时，按包名回退到固定的 CDN 下载地址
        if (downloadUrl.isEmpty()) {
            downloadUrl = fallbackDownloadUrl(pkName)
            if (downloadUrl.isNotEmpty()) {
                Log.d(TAG, "downloadUrl empty, fallback to: $downloadUrl")
            }
        }

        return VivoOtaResult(
            updateVersion = updateVersion,
            filename = pkName,
            fileSizeBytes = pkLen,
            fileSizeMb = sizeMb,
            downloadUrl = downloadUrl,
            changelogUrl = changelogUrl,
            securityPatch = securityPatch,
            updateDate = updateDate,
            md5 = md5,
            channel = channel.value,
            rawResponse = updateResponse
        )
    }

    private fun joinParams(params: Map<String, Any>): String {
        return params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    /** 生成 15 位 IMEI：优先使用系统属性 IMEI，长度不足 15 位则随机生成。 */
    private fun genImei(): String {
        val fromProp = System.getProperty("IMEI", "").trim()
        if (fromProp.length == 15 && fromProp.all { it.isDigit() }) {
            return fromProp
        }
        val sb = StringBuilder(15)
        val rand = Random()
        for (i in 0 until 15) sb.append(rand.nextInt(10))
        return sb.toString()
    }

    /** 兜底下载地址：`PK_BASE_URL` + 包名。包名缺失/为 "(Not found)" 时返回空串。 */
    private fun fallbackDownloadUrl(pkName: String): String {
        val name = pkName.trim().trimStart('/')
        if (name.isEmpty() || name == "(Not found)") return ""
        return PK_BASE_URL + name
    }

    private fun extractPkUrl(json: String): String? {
        val url = extractJsonStr(json, "pk\":\"")
        return if (url == "(Not found)") null else url
    }

    private fun extractJsonStr(json: String, key: String): String {
        val idx = json.indexOf(key)
        if (idx < 0) return "(Not found)"
        val start = idx + key.length
        val end = json.indexOf('"', start)
        if (end < 0) {
            val end2 = json.indexOf(',', start)
            val end3 = json.indexOf('}', start)
            val realEnd = if (end2 in start until end3) end2 else end3
            return if (realEnd < 0) json.substring(start) else json.substring(start, realEnd).trim()
        }
        return json.substring(start, end)
    }

    private fun extractFirstAvailable(json: String, keys: List<String>): String {
        for (key in keys) {
            val value = extractJsonStr(json, key)
            if (value != "(Not found)" && value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    private fun extractField(json: String, fieldNames: List<String>): String {
        for (name in fieldNames) {
            val strKey = "\"$name\":\""
            val strIdx = json.indexOf(strKey)
            if (strIdx >= 0) {
                val start = strIdx + strKey.length
                val end = json.indexOf('"', start)
                if (end > start) {
                    val value = json.substring(start, end).trim()
                    if (value.isNotEmpty()) return value
                }
            }
            val numKey = "\"$name\":"
            val numIdx = json.indexOf(numKey)
            if (numIdx >= 0) {
                val start = numIdx + numKey.length
                var s = start
                while (s < json.length && json[s].isWhitespace()) s++
                if (s < json.length && json[s] != ',' && json[s] != '}' && json[s] != ']' && json[s] != 'n') {
                    val end = json.indexOfAny(charArrayOf(',', '}', ']'), s)
                    val realEnd = if (end < 0) json.length else end
                    val value = json.substring(s, realEnd).trim().removeSurrounding("\"")
                    if (value.isNotEmpty() && value != "0" && value != "null") return value
                }
            }
        }
        return ""
    }

    // ================================================================
    // Protocol: encrypt → package → base64url
    // ================================================================

    private fun encryptToJvq(plaintext: String): String {
        val encrypted = VivoCrypto.encrypt(plaintext.toByteArray(StandardCharsets.UTF_8))
            ?: throw RuntimeException("Encryption failed")
        val pkg = buildProtocolPackage(5, 2, TOKEN_NATIVE, encrypted)
        return base64UrlEncode(pkg)
    }

    private fun decryptResponse(responseB64: String): String {
        val fullPackage = base64UrlDecode(responseB64)
        val encryptedBody = extractEncryptedBody(fullPackage)
        val decrypted = VivoCrypto.decrypt(encryptedBody)
            ?: throw RuntimeException("Decryption failed")
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private fun buildProtocolPackage(type: Int, keyVersion: Int, token: String, data: ByteArray): ByteArray {
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        val headerTotalLen = 16 + tokenBytes.size

        val headerFieldBos = java.io.ByteArrayOutputStream()
        val headerFieldDos = DataOutputStream(headerFieldBos)
        headerFieldDos.writeShort(1)
        headerFieldDos.writeByte(tokenBytes.size)
        headerFieldDos.write(tokenBytes)
        headerFieldDos.writeShort(keyVersion)
        headerFieldDos.writeByte(type)
        val headerFieldBytes = headerFieldBos.toByteArray()

        val crc32 = CRC32()
        crc32.update(headerFieldBytes)

        val bos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeShort(headerTotalLen)
        dos.writeLong(crc32.value)
        dos.write(headerFieldBytes)
        dos.write(data)
        return bos.toByteArray()
    }

    private fun extractEncryptedBody(fullPackage: ByteArray): ByteArray {
        val dis = DataInputStream(ByteArrayInputStream(fullPackage))
        val headerLen = dis.readUnsignedShort()
        val payloadLen = fullPackage.size - headerLen
        val payload = ByteArray(payloadLen)
        System.arraycopy(fullPackage, headerLen, payload, 0, payloadLen)
        return payload
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return java.util.Base64.getUrlEncoder().encodeToString(data)
    }

    private fun base64UrlDecode(data: String): ByteArray {
        val decoded = URLDecoder.decode(data, "UTF-8").replace('-', '+').replace('_', '/')
        val pad = 4 - decoded.length % 4
        val padded = if (pad != 4) decoded + "=".repeat(pad) else decoded
        return java.util.Base64.getDecoder().decode(padded)
    }

    // ================================================================
    // HTTP
    // ================================================================

    private fun sendFreshEncryptedRequest(plaintext: String, domain: Domain): String {
        val jvqParam = encryptToJvq(plaintext)
        val response = httpPost(domain.url("/vgc/v2/getVgcAndPatch.do"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    private fun requestRedirPost(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/pk/redirPost.do"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    // 尝鲜/公测/内测通道的尝鲜包查询（getTastePk），与 PC 版 sendTasteRequest 对应
    private fun sendTasteRequest(plaintext: String, domain: Domain): String {
        val jvqParam = encryptToJvq(plaintext)
        val response = httpPost(domain.url("/upgrade/trial/getTastePk"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    // 公测报名状态查询（beta/query）
    private fun sendBetaRequest(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/beta/query"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    // 内测报名状态查询（alpha/getAlphaState）
    private fun sendAlphaRequest(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/alpha/getAlphaState"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    private fun httpPost(urlString: String, body: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("User-Agent", "okhttp/4.3.23")
        conn.setRequestProperty("Host", url.host)
        conn.setRequestProperty("Connection", "Keep-Alive")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.doOutput = true

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val code = conn.responseCode
        val is_ = if (code >= 400) conn.errorStream else conn.inputStream
        val inputStream = if ("gzip" == conn.contentEncoding) GZIPInputStream(is_) else is_

        val resp = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) resp.append(line)
        }
        return resp.toString()
    }

    fun fetchChangelog(url: String): String? {
        if (url.isEmpty()) return null
        return try {
            val response = httpGet(url)
            Log.d(TAG, "Changelog response (${response.length} chars): ${response.take(500)}")
            if (response.trimStart().startsWith("{")) {
                parseChangelogJson(response)
            } else if (response.trimStart().startsWith("<")) {
                parseChangelogHtml(response)
            } else {
                response.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Changelog fetch failed: ${e.message}")
            null
        }
    }

    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val code = conn.responseCode
        val is_ = if (code >= 400) conn.errorStream else conn.inputStream
        val inputStream = if ("gzip" == conn.contentEncoding) GZIPInputStream(is_) else is_
        val resp = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) resp.append(line)
        }
        return resp.toString()
    }

    private fun parseChangelogJson(jsonText: String): String {
        val data = JSONObject(jsonText)
        val lines = mutableListOf<String>()

        data.optString("headContent", "").takeIf { it.isNotBlank() }?.let { lines.add(it) }

        val body = data.optJSONArray("body") ?: return lines.joinToString("\n")
        for (i in 0 until body.length()) {
            val item = body.optJSONObject(i) ?: continue
            appendChangelogSection(item, lines)
        }
        return lines.joinToString("\n").trim()
    }

    private fun appendChangelogSection(item: JSONObject, lines: MutableList<String>, level: Int = 0) {
        val title = item.optString("title", "").trim()
        if (title.isNotEmpty()) {
            if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
            lines.add(title)
        }

        val descContents = item.optJSONArray("descContents")
        if (descContents != null) {
            for (j in 0 until descContents.length()) {
                val entry = descContents.opt(j) ?: continue
                when (entry) {
                    is String -> if (entry.trim().isNotEmpty()) lines.add("  • ${entry.trim()}")
                    is JSONObject -> {
                        val content = entry.optString("content", "").ifEmpty { entry.optString("text", "") }
                        if (content.trim().isNotEmpty()) lines.add("  • ${content.trim()}")
                    }
                }
            }
        }

        val contentArr = item.optJSONArray("content")
        if (contentArr != null && descContents == null) {
            for (j in 0 until contentArr.length()) {
                val v = contentArr.opt(j)?.toString()?.trim()
                if (!v.isNullOrEmpty()) lines.add("  • $v")
            }
        }

        val children = item.optJSONArray("children")
        if (children != null) {
            for (k in 0 until children.length()) {
                val child = children.optJSONObject(k) ?: continue
                appendChangelogSection(child, lines, level + 1)
            }
        }
    }

    private fun parseChangelogHtml(html: String): String {
        var text = html
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<head[^>]*>[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<[^>]+>"), "")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&#39;", "'")
        text = text.replace(Regex("&#[0-9]+;"), "")
        text = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()
        return text
    }
}

private typealias JSONObject = org.json.JSONObject

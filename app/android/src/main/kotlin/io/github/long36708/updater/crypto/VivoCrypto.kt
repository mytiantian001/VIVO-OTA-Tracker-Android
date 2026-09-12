package io.github.long36708.updater.crypto

import android.content.Context
import android.util.Log
import com.vivo.seckeysdk.utils.NativeRequest
import com.vivo.seckeysdk.utils.NativeResponse
import com.vivo.seckeysdk.utils.SDKCipherNative
import java.net.HttpURLConnection
import java.net.URL

object VivoCrypto {

    private const val TAG = "VivoCrypto"
    private const val TARGET_PACKAGE = "com.bbk.updater"

    private var initialized = false
    private var useRemote = false
    private var serverUrl: String = ""

    fun init(ctx: Context): Boolean {
        if (initialized) return true
        val url = loadServerUrl(ctx)
        if (url.isNotEmpty()) {
            serverUrl = url
            useRemote = true
            initialized = testRemoteConnection()
            if (initialized) {
                Log.i(TAG, "Using remote crypto server: $url")
                return true
            }
            Log.w(TAG, "Remote server unreachable, trying native...")
        }
        useRemote = false
        return initNative(ctx)
    }

    fun encrypt(plaintext: ByteArray): ByteArray? {
        if (!initialized) return null
        return if (useRemote) remoteEncrypt(plaintext) else nativeEncrypt(plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (!initialized) return null
        return if (useRemote) remoteDecrypt(ciphertext) else nativeDecrypt(ciphertext)
    }

    fun setServerUrl(ctx: Context, url: String) {
        saveServerUrl(ctx, url)
        serverUrl = url
        useRemote = true
        initialized = false
    }

    fun clearServerUrl(ctx: Context) {
        saveServerUrl(ctx, "")
        serverUrl = ""
        useRemote = false
        initialized = false
    }

    private fun loadServerUrl(ctx: Context): String {
        return ctx.getSharedPreferences("vivo_crypto", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
    }

    private fun saveServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences("vivo_crypto", Context.MODE_PRIVATE)
            .edit().putString("server_url", url).apply()
    }

    private fun testRemoteConnection(): Boolean {
        return try {
            val url = URL("$serverUrl/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "Remote server not reachable: ${e.message}")
            false
        }
    }

    private fun remoteEncrypt(data: ByteArray): ByteArray? = remoteCall("/encrypt", data)

    private fun remoteDecrypt(data: ByteArray): ByteArray? = remoteCall("/decrypt", data)

    private fun remoteCall(endpoint: String, data: ByteArray): ByteArray? {
        try {
            val payload = JSONObject().apply {
                put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
            }
            val url = URL("$serverUrl$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                Log.e(TAG, "Remote call failed: HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val resp = JSONObject(body)
            if (resp.optBoolean("success")) {
                return android.util.Base64.decode(resp.getString("data"), android.util.Base64.NO_WRAP)
            }
            Log.e(TAG, "Remote error: ${resp.optString("error")}")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Remote call error", e)
            return null
        }
    }

    private fun initNative(ctx: Context): Boolean {
        try {
            val certBytes = SDKCipherNative.getCertBytes()
            val fakeCtx = FakeVivoContext(ctx, TARGET_PACKAGE, certBytes)
            val result = SDKCipherNative.init(fakeCtx)
            initialized = result
            Log.i(TAG, "Native init() => $result")
            return result
        } catch (e: Throwable) {
            Log.e(TAG, "Native init failed", e)
            return false
        }
    }

    private fun nativeEncrypt(data: ByteArray): ByteArray? {
        val req = NativeRequest(1, 1, 2, data)
        val resp = SDKCipherNative.execute(req)
        return if (resp?.err == 0) resp.output else null
    }

    private fun nativeDecrypt(data: ByteArray): ByteArray? {
        val req = NativeRequest(2, 1, 2, data)
        val resp = SDKCipherNative.execute(req) ?: return null
        return if (resp.err == 0) resp.output else null
    }
}

private typealias JSONObject = org.json.JSONObject

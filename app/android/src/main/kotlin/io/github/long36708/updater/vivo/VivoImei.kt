package io.github.long36708.updater.vivo

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Random

/**
 * 请求参数里 imei 字段的取值来源。
 *
 * 优先级：用户手动填写 > 本机读取 > 随机生成。
 *
 * 注意：自 Android 10 起，三方应用无法再获取 IMEI 这类不可重置标识符，
 * 即使授予 READ_PHONE_STATE，[readDevice] 在绝大多数设备上仍会返回空串，
 * 最终回退到随机值。这里的读取逻辑属于「能取到就取」的尽力而为（best effort）。
 */
object VivoImei {

    private const val TAG = "VivoImei"
    const val LENGTH = 15

    /** MainActivity 申请 READ_PHONE_STATE 后回填的结果；null = 弹窗尚未出结果。 */
    private val permissionResult = MutableStateFlow<Boolean?>(null)

    /** 部分厂商把 IMEI 挂在系统属性上，反射兜底时用。 */
    private val VENDOR_KEYS = listOf("ril.gsm.imei", "persist.radio.imei", "ro.ril.imei")

    /** 等待权限申请出结果（用户可能迟迟不处理弹窗，故有时限）。 */
    suspend fun awaitPhoneStatePermission(): Boolean {
        val granted = withTimeoutOrNull(15_000) { permissionResult.first { it != null } }
        return granted ?: false
    }

    fun setPermissionResult(granted: Boolean) {
        permissionResult.value = granted
    }

    /** 归一化：只保留数字，截断到 15 位。 */
    fun sanitize(value: String): String = value.filter { it.isDigit() }.take(LENGTH)

    /** 随机生成 15 位 IMEI（与原实现口径一致：纯随机数字，不做 Luhn 校验）。 */
    fun random(): String {
        val rand = Random()
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(rand.nextInt(10)) }
        return sb.toString()
    }

    /**
     * 尽力读取本机 IMEI，读不到返回空串。
     * 需先在 MainActivity 完成 READ_PHONE_STATE 申请，否则直接走系统属性兜底。
     */
    suspend fun readDevice(context: Context): String {
        val granted = awaitPhoneStatePermission()
        val fromTelephony = if (granted) readFromTelephony(context) else ""
        return if (fromTelephony.length == LENGTH) fromTelephony else readFromSystemProperty()
    }

    @Suppress("MissingPermission", "HardwareIds")
    private fun readFromTelephony(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ""
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.imei else tm.deviceId
            sanitize(raw.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager read failed: ${e.message}")
            ""
        }
    }

    /** 先查 JVM 系统属性 IMEI（历史行为），再反射查厂商只读属性。 */
    private fun readFromSystemProperty(): String {
        val fromProp = sanitize(System.getProperty("IMEI", "").trim())
        if (fromProp.length == LENGTH) return fromProp
        for (key in VENDOR_KEYS) {
            val value = sanitize(getSystemProperty(key))
            if (value.length == LENGTH) return value
        }
        return ""
    }

    private fun getSystemProperty(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val getter = clazz.getMethod("get", String::class.java, String::class.java)
        getter.invoke(clazz, key, "") as? String ?: ""
    } catch (e: Exception) {
        ""
    }
}

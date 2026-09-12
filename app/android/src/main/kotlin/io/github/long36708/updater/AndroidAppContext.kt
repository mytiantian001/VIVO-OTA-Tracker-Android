package io.github.long36708.updater

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager

@SuppressLint("StaticFieldLeak")
object AndroidAppContext {
    private var context: Context? = null

    fun init(context: Context) {
        AndroidAppContext.context = context.applicationContext
    }

    fun getApplicationContext(): Context? {
        return context
    }

    /** 应用版本名，例如 "1.3.0"，回退到 BuildConfig.APP_VERSION_NAME 以防包管理器读取失败 */
    val versionName: String
        get() = runCatching {
            val ctx = context ?: return@runCatching BuildConfig.APP_VERSION_NAME
            val pm = ctx.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, 0)
            }
            info.versionName ?: BuildConfig.APP_VERSION_NAME
        }.getOrDefault(BuildConfig.APP_VERSION_NAME)

    /** 应用版本号 (versionCode)，例如 42，回退到 0 以防包管理器读取失败 */
    val versionCode: Long
        get() = runCatching {
            val ctx = context ?: return@runCatching 0L
            val pm = ctx.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
}
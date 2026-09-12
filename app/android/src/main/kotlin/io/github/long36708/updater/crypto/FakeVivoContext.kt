package io.github.long36708.updater.crypto

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

class FakeVivoContext(
    base: Context,
    private val targetPackage: String,
    private val fakeCertBytes: ByteArray
) : ContextWrapper(base) {

    override fun getPackageName(): String = targetPackage

    override fun getPackageManager(): PackageManager {
        val real = baseContext.packageManager
        val realPackage = baseContext.packageName
        return FakePackageManager(real, targetPackage, realPackage, fakeCertBytes)
    }
}

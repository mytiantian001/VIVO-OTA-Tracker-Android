package io.github.long36708.updater

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.long36708.updater.vivo.VivoApp
import io.github.long36708.updater.vivo.VivoImei

class MainActivity : ComponentActivity() {

    /** 权限结果由 VivoImei 统一消费：ViewModel 在解析本机 IMEI 前会等待该结果。 */
    private val requestPhoneState =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            VivoImei.setPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAppContext.init(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            VivoApp()
        }
        requestPhoneState.launch(Manifest.permission.READ_PHONE_STATE)
    }
}

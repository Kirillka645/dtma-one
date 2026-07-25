package app.dtma.one

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.dtma.one.ui.DtmaAppRoot
import app.dtma.one.ui.theme.DtmaTheme
import app.dtma.one.vpn.DtmaVpnService

class MainActivity : ComponentActivity() {

    private var pendingStart by mutableStateOf(false)

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
        pendingStart = false
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent {
            DtmaTheme {
                DtmaAppRoot(
                    onToggleVpn = { enable ->
                        if (enable) requestAndStartVpn() else stopVpn()
                    },
                )
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestAndStartVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingStart = true
            vpnPermission.launch(prepare)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, DtmaVpnService::class.java).setAction(DtmaVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, DtmaVpnService::class.java).setAction(DtmaVpnService.ACTION_STOP)
        startService(intent)
    }
}

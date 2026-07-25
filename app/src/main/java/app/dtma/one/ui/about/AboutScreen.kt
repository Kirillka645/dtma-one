package app.dtma.one.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dtma.one.BuildConfig

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("About DTMA One", style = MaterialTheme.typography.headlineSmall)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Commit: ${BuildConfig.GIT_SHA}")
        Text("Source: ${BuildConfig.SOURCE_URL}")
        Text("License: Apache-2.0")
        Text("Package: ${BuildConfig.APPLICATION_ID}")

        Text("What this app is", style = MaterialTheme.typography.titleMedium)
        Text(
            "Local Android VpnService with PAER (Passive Adaptive Endpoint Racing). " +
                "No remote VPN server, proxy, relay, cloud intermediary, ads, or analytics.",
        )

        Text("Limitations", style = MaterialTheme.typography.titleMedium)
        Text(
            "• Does not guarantee bypass of blocking.\n" +
                "• Helps only when at least one legitimate endpoint is reachable.\n" +
                "• No TLS interception/MITM; third-party certs are not validated by DTMA One.\n" +
                "• QUIC endpoint racing: NOT_IMPLEMENTED (UDP still forwarded).\n" +
                "• HTTPS/SVCB full parsing depends on platform DNS exposure.\n" +
                "• ICMP not forwarded in MVP.\n" +
                "• Debug APK is for testing only.",
        )

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            "RVEC stores only hostname, endpoint parameters, timestamps, and minimal network context. " +
                "No SSID/BSSID/MAC/IMEI/IMSI. Backup disabled for RVEC. Export is manual and anonymized.",
        )
    }
}

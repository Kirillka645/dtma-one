package app.dtma.one.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dtma.one.BuildConfig
import app.dtma.one.R
import app.dtma.one.update.UpdateCheckState
import app.dtma.one.update.UpdateNotifier
import kotlinx.coroutines.launch

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateNotifier.state.collectAsStateWithLifecycle()

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

        OutlinedButton(
            onClick = {
                scope.launch { UpdateNotifier.check(context, force = true) }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = updateState !is UpdateCheckState.Checking,
        ) {
            Text(
                when (updateState) {
                    is UpdateCheckState.Checking -> stringResource(R.string.update_checking)
                    else -> stringResource(R.string.update_check_now)
                },
            )
        }
        when (val s = updateState) {
            is UpdateCheckState.UpToDate -> Text(stringResource(R.string.update_up_to_date))
            is UpdateCheckState.Available -> {
                Text(stringResource(R.string.update_banner_title, s.update.versionName))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(s.update.releaseUrl)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_banner_open))
                }
            }
            is UpdateCheckState.Error -> Text(
                stringResource(R.string.update_check_error, s.message),
            )
            else -> Unit
        }

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

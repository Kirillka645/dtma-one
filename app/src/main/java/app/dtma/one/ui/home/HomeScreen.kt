package app.dtma.one.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dtma.one.R
import app.dtma.one.core.model.VpnUiState
import app.dtma.one.update.UpdateCheckState
import app.dtma.one.update.UpdateNotifier
import app.dtma.one.vpn.VpnStateHolder
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onToggleVpn: (Boolean) -> Unit) {
    val status by VpnStateHolder.status.collectAsStateWithLifecycle()
    val updateState by UpdateNotifier.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = status.state == VpnUiState.ACTIVE ||
        status.state == VpnUiState.LIMITED ||
        status.state == VpnUiState.UNSTABLE ||
        status.state == VpnUiState.STARTING

    val stateLabel = when (status.state) {
        VpnUiState.OFF -> stringResource(R.string.state_off)
        VpnUiState.STARTING -> stringResource(R.string.state_starting)
        VpnUiState.ACTIVE -> stringResource(R.string.state_active)
        VpnUiState.LIMITED -> stringResource(R.string.state_limited)
        VpnUiState.UNSTABLE -> stringResource(R.string.state_unstable)
        VpnUiState.ERROR -> stringResource(R.string.state_error)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "DTMA One", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.disclaimer_short),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (updateState is UpdateCheckState.Available) {
            val update = (updateState as UpdateCheckState.Available).update
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_banner_title, update.versionName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.update_banner_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.update_banner_open))
                        }
                        TextButton(
                            onClick = {
                                scope.launch { UpdateNotifier.dismiss(context) }
                            },
                        ) {
                            Text(stringResource(R.string.update_banner_dismiss))
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (status.state) {
                    VpnUiState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                    VpnUiState.ERROR -> MaterialTheme.colorScheme.errorContainer
                    VpnUiState.LIMITED -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stateLabel, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = status.message.ifBlank { "-" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.system_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(text = "IPv4: ${status.ipv4} / IPv6: ${status.ipv6} / flows: ${status.flowCount}")
                status.networkContext?.let { ctx ->
                    Text(text = "Network: ${ctx.networkType}")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (active) {
            OutlinedButton(
                onClick = { onToggleVpn(false) },
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {
                Text(text = stringResource(R.string.btn_disable), style = MaterialTheme.typography.titleLarge)
            }
        } else {
            Button(
                onClick = { onToggleVpn(true) },
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {
                Text(text = stringResource(R.string.btn_enable), style = MaterialTheme.typography.titleLarge)
            }
        }

        Text(
            text = stringResource(R.string.no_cert_claim),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

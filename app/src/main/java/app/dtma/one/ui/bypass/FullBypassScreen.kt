package app.dtma.one.ui.bypass

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dtma.one.R
import app.dtma.one.bypass.WarpController
import app.dtma.one.bypass.WarpInstaller
import app.dtma.one.core.model.VpnUiState
import app.dtma.one.vpn.VpnStateHolder
import kotlinx.coroutines.launch

/**
 * In-app free Cloudflare WARP (embedded WireGuard). No external WireGuard app required.
 */
@Composable
fun FullBypassScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val vpnStatus by VpnStateHolder.status.collectAsStateWithLifecycle()
    val warpOn = WarpController.isRunning ||
        (vpnStatus.state == VpnUiState.ACTIVE && vpnStatus.message.contains("WARP", ignoreCase = true))

    var pendingStart by remember { mutableStateOf(false) }

    fun doStartWarp() {
        busy = true
        status = context.getString(R.string.warp_enabling)
        scope.launch {
            val result = WarpController.start(context)
            status = result.fold(
                onSuccess = {
                    "WARP внутри DTMA включён.\n" +
                        "Проверьте YouTube и Telegram (лучше LTE).\n" +
                        "Локальный hev-VPN при этом выключен."
                },
                onFailure = { e ->
                    "Ошибка: ${e.message}\n" +
                        "Попробуйте «Новый аккаунт WARP» или приложение 1.1.1.1."
                },
            )
            busy = false
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingStart) {
            pendingStart = false
            doStartWarp()
        } else {
            pendingStart = false
            busy = false
            status = "Нужно разрешение VPN для WARP"
        }
    }

    fun requestAndStart() {
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            pendingStart = true
            busy = true
            vpnPermission.launch(prepare)
        } else {
            doStartWarp()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Чтобы всё работало", style = MaterialTheme.typography.headlineSmall)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Text(
                modifier = Modifier.padding(12.dp),
                text = "Cloudflare WARP встроен в DTMA — отдельный WireGuard не нужен.\n" +
                    "Трафик идёт через сеть Cloudflare (не сервер DTMA). " +
                    "Локальный режим hev при старте WARP отключается.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (warpOn) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        WarpController.stop(context)
                        status = "WARP выключен"
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.warp_disable))
            }
        } else {
            Button(
                onClick = { requestAndStart() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (busy) stringResource(R.string.warp_enabling)
                    else stringResource(R.string.warp_enable),
                )
            }
        }

        OutlinedButton(
            onClick = {
                WarpController.clearCachedConfig()
                status = "Кэш WARP сброшен. Нажмите «Включить WARP» для новой регистрации."
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !warpOn,
        ) {
            Text("Новый аккаунт WARP (сброс conf)")
        }

        Text(
            "После включения: откройте YouTube и Telegram. " +
                "Если WARP API недоступен в вашей сети — способ ниже.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Запасной путь", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = {
                WarpInstaller.openCloudflareWarpApp(context)
                status = "Включите WARP в 1.1.1.1. DTMA VPN/WARP выключите, чтобы не конфликтовать."
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Приложение Cloudflare 1.1.1.1 (если in-app не взлетел)")
        }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://settings")))
                } catch (_: Exception) {
                    status = "Telegram → Настройки → Данные → Прокси → MTProto"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("MTProto в Telegram")
        }

        if (status.isNotBlank()) {
            Card {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            "Статус: ${vpnStatus.state} — ${vpnStatus.message.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

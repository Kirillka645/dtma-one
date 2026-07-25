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

@Composable
fun FullBypassScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val vpnStatus by VpnStateHolder.status.collectAsStateWithLifecycle()
    val warpOn = WarpController.isRunning

    var pendingStart by remember { mutableStateOf(false) }

    fun doStartWarp() {
        busy = true
        status = "Запуск WARP + проверка handshake…"
        scope.launch {
            val result = WarpController.start(context)
            status = result.fold(
                onSuccess = {
                    "WARP реально поднялся (Cloudflare ответил на handshake).\n" +
                        "Проверьте YouTube и Telegram.\n" +
                        "Если всё ещё нет — UDP 2408 режется, нужен MTProto/другая сеть.\n" +
                        WarpController.statusLine()
                },
                onFailure = { e ->
                    "НЕ включён (раньше могло писать «OK» без handshake):\n${e.message}\n\n" +
                        "1) «Новый аккаунт WARP»\n" +
                        "2) Только LTE\n" +
                        "3) Запасной 1.1.1.1 app\n" +
                        "4) MTProto для Telegram"
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
            status = "Нужно разрешение VPN"
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
        Text("WARP внутри DTMA", style = MaterialTheme.typography.headlineSmall)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Text(
                modifier = Modifier.padding(12.dp),
                text = "0.3.0: не считаем WARP «включённым», пока Cloudflare не ответил (rx>0). " +
                    "IPv4-only + IP endpoint. WireGuard-приложение не нужно.",
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
                status = "Кэш сброшен. Включите WARP снова (новая регистрация)."
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !warpOn,
        ) {
            Text("Новый аккаунт WARP")
        }

        Text("Запасные пути", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = {
                WarpInstaller.openCloudflareWarpApp(context)
                status = "В 1.1.1.1 включите WARP. DTMA WARP выключите."
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Приложение 1.1.1.1 WARP")
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
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (warpOn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            "UI: ${vpnStatus.state} — ${vpnStatus.message.ifBlank { "—" }}\n${WarpController.statusLine()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

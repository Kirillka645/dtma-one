package app.dtma.one.ui.bypass

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dtma.one.R
import app.dtma.one.bypass.WarpController
import app.dtma.one.bypass.WarpInstaller
import app.dtma.one.bypass.WarpMode
import app.dtma.one.vpn.VpnStateHolder
import kotlinx.coroutines.launch

@Composable
fun FullBypassScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var autoReconnect by remember { mutableStateOf(WarpController.autoReconnect) }

    val warp by WarpController.status.collectAsStateWithLifecycle()
    val vpnStatus by VpnStateHolder.status.collectAsStateWithLifecycle()

    fun doStart(forceNew: Boolean) {
        busy = true
        note = "Быстрый старт: DNS bootstrap + до 3 аккаунтов × 3 endpoint…"
        scope.launch {
            val result = WarpController.start(context, forceNewAccount = forceNew)
            note = result.fold(
                onSuccess = {
                    "Готово: ${WarpController.status.value.modeLabelRu()}\n" +
                        WarpController.statusLine()
                },
                onFailure = { e ->
                    "Не удалось после авто-попыток:\n${e.message}\n\n" +
                        "Если «Таймаут CF API» — API регистрации режется.\n" +
                        "• LTE / другой Wi‑Fi\n" +
                        "• «Вставить conf из буфера» (получите conf через 1.1.1.1 / wgcf на нормальной сети)\n" +
                        "• MTProto в Telegram"
                },
            )
            busy = false
        }
    }

    fun doStartFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank() || !text.contains("[Interface]", ignoreCase = true)) {
            note = "Скопируйте WireGuard conf (с [Interface]/[Peer]/PrivateKey/Endpoint) в буфер"
            return
        }
        busy = true
        note = "Импорт conf без Cloudflare API…"
        scope.launch {
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                pendingStart = false
                busy = false
                note = "Сначала выдайте VPN-разрешение кнопкой «Включить WARP», затем снова вставьте conf"
                return@launch
            }
            val result = WarpController.startFromConf(context, text)
            note = result.fold(
                onSuccess = {
                    "Conf импортирован:\n${WarpController.statusLine()}"
                },
                onFailure = { e ->
                    "Conf не принят: ${e.message}"
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
            doStart(forceNew = false)
        } else {
            pendingStart = false
            busy = false
            note = "Нужно разрешение VPN"
        }
    }

    fun requestAndStart(forceNew: Boolean) {
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            pendingStart = true
            busy = true
            vpnPermission.launch(prepare)
        } else {
            doStart(forceNew)
        }
    }

    val statusColor = when (warp.mode) {
        WarpMode.ON -> MaterialTheme.colorScheme.primaryContainer
        WarpMode.STARTING -> MaterialTheme.colorScheme.tertiaryContainer
        WarpMode.UNHEALTHY -> MaterialTheme.colorScheme.errorContainer
        WarpMode.ERROR -> MaterialTheme.colorScheme.errorContainer
        WarpMode.OFF -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "WARP внутри DTMA",
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(colors = CardDefaults.cardColors(containerColor = statusColor)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Статус: ${warp.modeLabelRu()}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(text = warp.message, style = MaterialTheme.typography.bodyMedium)
                Text(text = warp.trafficLine(), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Endpoint: ${warp.endpoint ?: "—"}\n" +
                        "Адрес: ${warp.address ?: "—"}\n" +
                        "Попыток: ${warp.startAttempts} · авто-regen: ${warp.autoRegenUsed}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val err = warp.lastError
                if (!err.isNullOrBlank()) {
                    Text(
                        text = "Ошибка: $err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (warp.mode == WarpMode.ON || warp.mode == WarpMode.UNHEALTHY) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        WarpController.stop(context)
                        note = "WARP выключен"
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.warp_disable))
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        WarpController.recheck(context)
                        note = "Проверка: ${WarpController.statusLine()}"
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Проверить сейчас (ВКЛ/ВЫКЛ)")
            }
        } else {
            Button(
                onClick = { requestAndStart(forceNew = false) },
                enabled = !busy && warp.mode != WarpMode.STARTING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (busy || warp.mode == WarpMode.STARTING) {
                        stringResource(R.string.warp_enabling)
                    } else {
                        stringResource(R.string.warp_enable)
                    },
                )
            }
        }

        OutlinedButton(
            onClick = { requestAndStart(forceNew = true) },
            enabled = !busy && warp.mode != WarpMode.STARTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Новый аккаунт + включить")
        }

        OutlinedButton(
            onClick = { doStartFromClipboard() },
            enabled = !busy && warp.mode != WarpMode.STARTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Вставить conf из буфера (без API)")
        }

        Text(text = "Фичи", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = "Авто-reconnect", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Если WARP «заболел» (нет RX) — новый conf и перезапуск",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoReconnect,
                onCheckedChange = { checked ->
                    autoReconnect = checked
                    WarpController.autoReconnect = checked
                },
            )
        }

        Text(
            text = "• DNS bootstrap + DoH + multi-IP race\n" +
                "• TCP/443 pre-probe + Wi‑Fi/LTE race + HTTP/1.1\n" +
                "• Conf сохраняется — после 1 успеха API не нужен\n" +
                "• Импорт conf из буфера если API заблокирован\n" +
                "• Handshake ≤3 endpoint (2408/443/500)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(text = "Запасные пути", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = {
                WarpInstaller.openCloudflareWarpApp(context)
                note = "В 1.1.1.1 включите WARP. DTMA WARP выключите."
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Приложение 1.1.1.1 WARP")
        }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://settings")))
                } catch (_: Exception) {
                    note = "Telegram → Прокси → MTProto"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "MTProto в Telegram")
        }

        if (note.isNotBlank()) {
            Card {
                Text(
                    text = note,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            text = "VpnUI: ${vpnStatus.state} — ${vpnStatus.message}\n${WarpController.statusLine()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

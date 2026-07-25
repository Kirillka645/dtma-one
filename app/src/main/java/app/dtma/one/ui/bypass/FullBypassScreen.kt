package app.dtma.one.ui.bypass

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
import androidx.compose.ui.unit.dp
import app.dtma.one.bypass.WarpConfigGenerator
import app.dtma.one.bypass.WarpInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Practical path when local DTMA cannot reach TG/YouTube:
 * free Cloudflare WARP via WireGuard (external egress), not another local remap.
 */
@Composable
fun FullBypassScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confFile by remember { mutableStateOf<File?>(null) }

    val wgInstalled = remember { WarpInstaller.isInstalled(context, WarpInstaller.WIREGUARD_PKG) }
    val warpAppInstalled = remember {
        WarpInstaller.isInstalled(context, WarpInstaller.CLOUDFLARE_WARP_PKG)
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
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                modifier = Modifier.padding(12.dp),
                text = "У вас: Wi‑Fi TG 0/26, LTE 2/26, YouTube DoH ✓ но видео нет, " +
                    "без VPN оба мертвы.\n\n" +
                    "Локальный DTMA это НЕ починит. Нужен выход через ЧУЖУЮ сеть " +
                    "(Cloudflare WARP бесплатно). Ниже — автогенерация WireGuard-конфига WARP.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text("Способ 1 — WARP через WireGuard (рекомендуется)", style = MaterialTheme.typography.titleMedium)
        Text(
            "1) Установите WireGuard\n" +
                "2) Сгенерируйте conf (аккаунт Cloudflare free)\n" +
                "3) Импорт → включите туннель\n" +
                "4) DTMA VPN выключите (двойной туннель ломает сеть)\n" +
                "5) Проверьте YouTube + Telegram",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!wgInstalled) {
            Button(
                onClick = { WarpInstaller.openPlayStore(context, WarpInstaller.WIREGUARD_PKG) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Установить WireGuard")
            }
        } else {
            Text("WireGuard установлен ✓", color = MaterialTheme.colorScheme.primary)
        }

        Button(
            onClick = {
                busy = true
                status = "Регистрация free WARP…"
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            WarpConfigGenerator.generate()
                        }
                        val f = withContext(Dispatchers.IO) {
                            WarpInstaller.writeConf(context, result.confText)
                        }
                        confFile = f
                        status = "Готово: ${result.addressV4} → ${result.endpoint}\n" +
                            WarpInstaller.openInWireGuard(context, f)
                    } catch (e: Exception) {
                        status = "Ошибка WARP: ${e.message}\n" +
                            "Попробуйте способ 2 (приложение 1.1.1.1 WARP) или другую сеть."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Генерация…" else "Сгенерировать WARP и открыть")
        }

        confFile?.let { f ->
            OutlinedButton(
                onClick = {
                    status = WarpInstaller.openInWireGuard(context, f)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Снова открыть conf в WireGuard")
            }
        }

        Text("Способ 2 — приложение Cloudflare 1.1.1.1", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                if (warpAppInstalled) {
                    WarpInstaller.openCloudflareWarpApp(context)
                    status = "Включите WARP (не только DNS) в приложении 1.1.1.1"
                } else {
                    WarpInstaller.openPlayStore(context, WarpInstaller.CLOUDFLARE_WARP_PKG)
                    status = "Установите 1.1.1.1 → WARP ON → DTMA OFF"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (warpAppInstalled) "Открыть 1.1.1.1 WARP" else "Установить 1.1.1.1 WARP")
        }

        Text("Способ 3 — только Telegram (MTProto)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Если WARP недоступен: в Telegram → Настройки → Данные → Прокси → MTProto. " +
                "Нужен рабочий secret (свой VPS или проверенный источник).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("tg://settings")),
                    )
                } catch (_: Exception) {
                    status = "Откройте Telegram → Настройки → Данные и память → Прокси"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Открыть настройки Telegram")
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
            "Важно: free WARP — сеть Cloudflare, не сервер DTMA. " +
                "Условия Cloudflare / лимиты / блокировки WARP в вашей стране возможны. " +
                "При отказе API — используйте приложение 1.1.1.1.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

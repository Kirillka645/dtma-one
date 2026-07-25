package app.dtma.one.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dtma.one.DtmaApp
import app.dtma.one.core.model.LocalMetrics
import app.dtma.one.core.model.PassiveHypothesisEngine
import java.io.File
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsScreen() {
    val settings by DtmaApp.instance.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = app.dtma.one.core.storage.UserSettings(),
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var socksHost by remember(settings.upstreamSocksHost) {
        mutableStateOf(settings.upstreamSocksHost)
    }
    var socksPort by remember(settings.upstreamSocksPort) {
        mutableStateOf(settings.upstreamSocksPort.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PAER settings", style = MaterialTheme.typography.titleLarge)
        Text("Race width (max 3)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { w ->
                FilterChip(
                    selected = settings.raceWidth == w,
                    onClick = {
                        scope.launch {
                            DtmaApp.instance.settingsRepository.update { it.copy(raceWidth = w) }
                        }
                    },
                    label = { Text("$w") },
                )
            }
        }
        RowSwitch(
            title = "Battery saver",
            checked = settings.batterySaver,
            onChecked = { v ->
                scope.launch {
                    DtmaApp.instance.settingsRepository.update { it.copy(batterySaver = v) }
                }
            },
        )
        RowSwitch(
            title = "Local logs (off by default)",
            checked = settings.localLogsEnabled,
            onChecked = { v ->
                scope.launch {
                    DtmaApp.instance.settingsRepository.update { it.copy(localLogsEnabled = v) }
                }
            },
        )
        RowSwitch(
            title = "Remember last test URL",
            checked = settings.rememberTestUrl,
            onChecked = { v ->
                scope.launch {
                    DtmaApp.instance.settingsRepository.update { it.copy(rememberTestUrl = v) }
                }
            },
        )

        Text("Обход блокировок Telegram", style = MaterialTheme.typography.titleMedium)
        Text(
            "Если probe DC = 0/N, локальный режим бессилен (тот же ISP). " +
                "Укажите СВОЙ SOCKS5 (VPS/прокси, где DC уже открыты). " +
                "DTMA One не предоставляет сервер и не хранит ваши ключи у «нас».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowSwitch(
            title = "Использовать upstream SOCKS5",
            checked = settings.upstreamSocksEnabled,
            onChecked = { v ->
                scope.launch {
                    DtmaApp.instance.settingsRepository.update { it.copy(upstreamSocksEnabled = v) }
                }
            },
        )
        OutlinedTextField(
            value = socksHost,
            onValueChange = { socksHost = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SOCKS5 host (IP или домен)") },
            singleLine = true,
            enabled = settings.upstreamSocksEnabled,
        )
        OutlinedTextField(
            value = socksPort,
            onValueChange = { socksPort = it.filter { ch -> ch.isDigit() }.take(5) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SOCKS5 port") },
            singleLine = true,
            enabled = settings.upstreamSocksEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(
            onClick = {
                scope.launch {
                    val port = socksPort.toIntOrNull() ?: 1080
                    DtmaApp.instance.settingsRepository.update {
                        it.copy(
                            upstreamSocksHost = socksHost.trim(),
                            upstreamSocksPort = port,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = settings.upstreamSocksEnabled,
        ) {
            Text("Сохранить SOCKS5")
        }
        Text(
            "После смены SOCKS5: Выключить VPN → Включить снова. " +
                "Альтернатива без DTMA: MTProto proxy в настройках Telegram.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = {
                scope.launch { DtmaApp.instance.rvecStore.clear() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clear RVEC cache")
        }
        Button(
            onClick = {
                scope.launch {
                    val hypo = PassiveHypothesisEngine().evaluate(emptyList())
                    val json = JSONObject()
                        .put("app", "DTMA One")
                        .put("version", app.dtma.one.BuildConfig.VERSION_NAME)
                        .put("note", "Anonymized metrics only")
                        .put("insufficientDataRate", hypo.insufficientData)
                        .put("primaryHypothesis", hypo.primaryHypothesis.name)
                        .put("rvecEntries", DtmaApp.instance.rvecStore.listAll(50).size)
                        .put("timeToTransportMs", LocalMetrics().timeToTransportMs)
                        .toString(2)
                    val file = File(context.cacheDir, "dtma-metrics-export.json")
                    file.writeText(json)
                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        file,
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Export metrics"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export anonymized JSON report")
        }
    }
}

@Composable
private fun RowSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

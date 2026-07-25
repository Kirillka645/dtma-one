package app.dtma.one.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PAER settings", style = MaterialTheme.typography.titleLarge)
        Text("Race width (max 3)")
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            title = "Battery saver (width≤2, 500ms delay, no background probes)",
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
            title = "Remember last test URL (explicit consent)",
            checked = settings.rememberTestUrl,
            onChecked = { v ->
                scope.launch {
                    DtmaApp.instance.settingsRepository.update { it.copy(rememberTestUrl = v) }
                }
            },
        )
        Text("RVEC half-life: ${settings.rvecHalfLifeHours}h (default 6)")
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
                    val metrics = LocalMetrics()
                    val hypo = PassiveHypothesisEngine().evaluate(emptyList())
                    val json = JSONObject()
                        .put("app", "DTMA One")
                        .put("version", app.dtma.one.BuildConfig.VERSION_NAME)
                        .put("note", "Anonymized metrics only; no URL path/query/headers/cookies/certs/SSID")
                        .put("insufficientDataRate", hypo.insufficientData)
                        .put("primaryHypothesis", hypo.primaryHypothesis.name)
                        .put("rvecEntries", DtmaApp.instance.rvecStore.listAll(50).size)
                        .put("timeToTransportMs", metrics.timeToTransportMs)
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
        Text(
            "ACTIVE_BACKGROUND_PROBES=0. No ads, analytics, or developer telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

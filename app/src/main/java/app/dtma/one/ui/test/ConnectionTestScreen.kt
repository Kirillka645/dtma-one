package app.dtma.one.ui.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dtma.one.DtmaApp
import app.dtma.one.R
import app.dtma.one.core.model.CandidatePlanner
import app.dtma.one.core.model.ScoringWeights
import app.dtma.one.core.network.NetworkContextFactory
import app.dtma.one.core.network.dns.DnsResolveResult
import app.dtma.one.core.network.dns.SystemDnsResolver
import app.dtma.one.core.network.https.StrictHttpsTester
import app.dtma.one.vpn.TelegramDcProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ConnectionTestScreen() {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://example.com/") }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(listOf<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.builtin_tls_label), style = MaterialTheme.typography.titleMedium)
        Text(
            "Strict platform TLS only. Invalid certificates are never accepted. " +
                "PAER races connect+TLS probes; one application request to the winner.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("HTTPS URL") },
            singleLine = true,
            enabled = !running,
        )
        Button(
            onClick = {
                scope.launch {
                    running = true
                    events = emptyList()
                    summary = "Resolving…"
                    try {
                        val ctx = NetworkContextFactory.current(DtmaApp.instance)
                        val host = runCatching {
                            val u = if (url.startsWith("http")) url else "https://$url"
                            java.net.URI(u).host
                        }.getOrNull().orEmpty()
                        val dns = SystemDnsResolver().resolve(host, 443, ctx.id)
                        val dnsList = when (dns) {
                            is DnsResolveResult.Ok -> dns.candidates
                            else -> emptyList()
                        }
                        val rvec = DtmaApp.instance.rvecStore.listForHost(host)
                        val settings = DtmaApp.instance.settingsRepository.settings.first()
                        val planned = CandidatePlanner.plan(
                            dnsCandidates = dnsList,
                            rvecCandidates = rvec,
                            nowMs = System.currentTimeMillis(),
                            currentContextId = ctx.id,
                            weights = ScoringWeights.DEFAULT.copy(
                                cacheHalfLifeHours = settings.rvecHalfLifeHours,
                            ),
                        )
                        val tester = StrictHttpsTester(settings.toRaceConfig())
                        val result = tester.test(url, planned) { e ->
                            events = events + "${e.stage}: ${e.detail}"
                        }
                        summary = buildString {
                            append(if (result.success) "OK" else "FAIL")
                            append(" · ")
                            append(result.message)
                            result.winner?.let {
                                append("\nWinner: ${it.ipAddress} (${it.source})")
                            }
                            result.statusCode?.let { append("\nHTTP $it") }
                            append("\nTLS validated by DTMA One: ${result.tlsValidatedByDtma}")
                        }
                        if (result.success && result.winner != null) {
                            DtmaApp.instance.rvecStore.saveSuccess(
                                result.winner!!,
                                System.currentTimeMillis(),
                                ctx.id,
                            )
                        } else if (result.winner != null && result.failureStage != null) {
                            DtmaApp.instance.rvecStore.saveFailure(
                                result.winner!!,
                                System.currentTimeMillis(),
                                ctx.id,
                                result.failureStage!!,
                            )
                        }
                        val settingsRepo = DtmaApp.instance.settingsRepository
                        val s = settingsRepo.settings.first()
                        if (s.rememberTestUrl) {
                            settingsRepo.update { it.copy(lastTestUrl = url) }
                        }
                    } catch (e: Exception) {
                        summary = "Error: ${e.message}"
                    } finally {
                        running = false
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Running…" else "Run PAER HTTPS test")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    running = true
                    events = emptyList()
                    summary = "Probing Telegram DCs…"
                    try {
                        val results = TelegramDcProbe.probeAll()
                        summary = TelegramDcProbe.summarize(results)
                        events = results.map {
                            "${if (it.ok) "OK" else "FAIL"} ${it.target.label} ${it.target.host} ${it.error ?: "${it.ms}ms"}"
                        }
                    } catch (e: Exception) {
                        summary = "Probe error: ${e.message}"
                    } finally {
                        running = false
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Проверить DC Telegram (диагностика)")
        }
        Text(
            "Локальный VPN выходит через того же провайдера. " +
                "Если все DC Telegram недоступны — DTMA One их не «откроет».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (summary.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(summary, modifier = Modifier.padding(12.dp))
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(events) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

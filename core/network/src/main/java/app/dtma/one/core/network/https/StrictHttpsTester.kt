package app.dtma.one.core.network.https

import app.dtma.one.core.model.ApplicationRequestGuard
import app.dtma.one.core.model.CandidateState
import app.dtma.one.core.model.EndpointCandidate
import app.dtma.one.core.model.FailureStage
import app.dtma.one.core.model.HttpStatusPolicy
import app.dtma.one.core.model.LocalMetrics
import app.dtma.one.core.model.RaceConfig
import app.dtma.one.core.model.RaceCoordinatorLogic
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Built-in HTTPS test with strict platform TLS validation (no custom TrustManager).
 *
 * OkHttp does not expose a stable API to open multiple TLS sockets and reuse only the
 * winner for the application request. PAER therefore:
 * 1) races safe connect+TLS probes (no user request body);
 * 2) sends exactly one application GET to the winner.
 * See docs/FEASIBILITY.md.
 */
class StrictHttpsTester(
    private val raceConfig: RaceConfig = RaceConfig(),
) {
    data class StageEvent(
        val stage: String,
        val detail: String,
        val candidateIp: String? = null,
    )

    data class TestResult(
        val success: Boolean,
        val urlHost: String,
        val statusCode: Int?,
        val winner: EndpointCandidate?,
        val failureStage: FailureStage?,
        val message: String,
        val metrics: LocalMetrics,
        val events: List<StageEvent>,
        val tlsValidatedByDtma: Boolean = true,
    )

    private data class ProbeOutcome(
        val candidate: EndpointCandidate,
        val ok: Boolean,
        val stage: FailureStage?,
        val transportMs: Long?,
        val secureMs: Long?,
        val alpn: String?,
    )

    suspend fun test(
        url: String,
        candidates: List<EndpointCandidate>,
        onEvent: (StageEvent) -> Unit = {},
    ): TestResult = withContext(Dispatchers.IO) {
        val events = mutableListOf<StageEvent>()
        fun emit(stage: String, detail: String, ip: String? = null) {
            val e = StageEvent(stage, detail, ip)
            events += e
            onEvent(e)
        }

        val normalized =
            if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val uri = runCatching { java.net.URI(normalized) }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank()) {
            return@withContext TestResult(
                false, "", null, null, FailureStage.UNKNOWN_FAILURE,
                "Invalid URL host", LocalMetrics(), events,
            )
        }
        if (!normalized.startsWith("https://")) {
            return@withContext TestResult(
                false, host, null, null, FailureStage.UNKNOWN_FAILURE,
                "Only HTTPS is supported for strict TLS test", LocalMetrics(), events,
            )
        }

        val plan = RaceCoordinatorLogic(raceConfig).buildPlan(candidates)
        emit("PLAN", "Racing ${plan.scheduled.size} candidates")
        if (plan.scheduled.isEmpty()) {
            return@withContext TestResult(
                false, host, null, null, FailureStage.DNS_EMPTY,
                "No candidates to race", LocalMetrics(), events,
            )
        }

        val raceStart = System.currentTimeMillis()
        val winnerHolder = AtomicReference<EndpointCandidate?>(null)
        var startedCount = 0
        var cancelledCount = 0
        var failedCount = 0

        val outcomes = coroutineScope {
            plan.scheduled.map { item ->
                async {
                    if (item.startDelayMs > 0) delay(item.startDelayMs)
                    if (winnerHolder.get() != null) {
                        cancelledCount++
                        return@async ProbeOutcome(
                            item.candidate.copy(currentState = CandidateState.CANCELLED),
                            false,
                            FailureStage.CANCELLED_BY_RACE,
                            null,
                            null,
                            null,
                        )
                    }
                    startedCount++
                    emit(
                        "CONNECTING",
                        "TCP+TLS ${item.candidate.ipAddress}:${item.candidate.port}",
                        item.candidate.ipAddress,
                    )
                    val result = probeSecureChannel(item.candidate, host)
                    if (result.ok) {
                        winnerHolder.compareAndSet(null, item.candidate)
                    } else if (result.stage != FailureStage.CANCELLED_BY_RACE) {
                        failedCount++
                        emit("FAILED", result.stage?.name ?: "fail", item.candidate.ipAddress)
                    }
                    result
                }
            }.awaitAll()
        }

        val best = outcomes.firstOrNull { it.ok }
            ?: return@withContext TestResult(
                false,
                host,
                null,
                null,
                outcomes.firstOrNull()?.stage ?: FailureStage.TRANSPORT_TIMEOUT,
                "No candidate completed secure channel",
                LocalMetrics(
                    numberOfStartedCandidates = startedCount,
                    numberOfCancelledCandidates = cancelledCount,
                    numberOfFailedCandidates = failedCount,
                    averageRaceDurationMs = (System.currentTimeMillis() - raceStart).toDouble(),
                ),
                events,
            )

        val winner = best.candidate.copy(currentState = CandidateState.SELECTED)
        emit("SELECTED", "Winner ${winner.ipAddress} alpn=${best.alpn}", winner.ipAddress)

        val guard = ApplicationRequestGuard()
        var status: Int? = null
        var appError: FailureStage? = null
        var appMessage = "OK"
        guard.trySendOnce {
            try {
                emit("HTTP", "Single application request to winner", winner.ipAddress)
                val client = OkHttpClient.Builder()
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            if (hostname.equals(host, ignoreCase = true)) {
                                return listOf(InetAddress.getByName(winner.ipAddress))
                            }
                            return Dns.SYSTEM.lookup(hostname)
                        }
                    })
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val req = Request.Builder()
                    .url(normalized)
                    .header("User-Agent", "DTMA-One/0.1 (strict-https-test)")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    status = resp.code
                    appMessage = if (HttpStatusPolicy.isNetworkSuccess(resp.code)) {
                        "HTTP ${resp.code} (network success; 4xx/5xx do not lower endpoint score)"
                    } else {
                        "Unexpected status ${resp.code}"
                    }
                }
            } catch (e: CertificateException) {
                appError = FailureStage.TLS_VALIDATION_FAILED
                appMessage = "Certificate validation failed"
            } catch (e: SSLHandshakeException) {
                appError = FailureStage.TLS_VALIDATION_FAILED
                appMessage = "TLS validation failed: ${e.message}"
            } catch (e: IOException) {
                appError = FailureStage.APPLICATION_TIMEOUT
                appMessage = "Application I/O: ${e.message}"
            }
        }

        val success = status != null && appError != FailureStage.TLS_VALIDATION_FAILED
        TestResult(
            success = success,
            urlHost = host,
            statusCode = status,
            winner = winner,
            failureStage = appError,
            message = appMessage,
            metrics = LocalMetrics(
                timeToTransportMs = best.transportMs,
                timeToSecureChannelMs = best.secureMs,
                winnerSource = winner.source,
                winnerIpFamily = winner.ipFamily,
                winnerTransport = winner.transport,
                numberOfStartedCandidates = startedCount,
                numberOfCancelledCandidates = cancelledCount,
                numberOfFailedCandidates = failedCount,
                averageRaceDurationMs = (System.currentTimeMillis() - raceStart).toDouble(),
            ),
            events = events,
            tlsValidatedByDtma = true,
        )
    }

    private fun probeSecureChannel(candidate: EndpointCandidate, hostname: String): ProbeOutcome {
        val t0 = System.currentTimeMillis()
        return try {
            val plain = Socket()
            plain.soTimeout = 8_000
            plain.connect(InetSocketAddress(candidate.ipAddress, candidate.port), 8_000)
            val transportMs = System.currentTimeMillis() - t0

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, hostname, candidate.port, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(hostname))
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()

            val secureMs = System.currentTimeMillis() - t0
            val alpn = if (android.os.Build.VERSION.SDK_INT >= 29) {
                runCatching { ssl.applicationProtocol }.getOrNull()
            } else {
                null
            }
            runCatching { ssl.close() }
            ProbeOutcome(candidate, true, null, transportMs, secureMs, alpn)
        } catch (_: CertificateException) {
            ProbeOutcome(candidate, false, FailureStage.TLS_VALIDATION_FAILED, null, null, null)
        } catch (e: SSLHandshakeException) {
            ProbeOutcome(candidate, false, FailureStage.TLS_VALIDATION_FAILED, null, null, null)
        } catch (_: java.net.SocketTimeoutException) {
            ProbeOutcome(candidate, false, FailureStage.TRANSPORT_TIMEOUT, null, null, null)
        } catch (_: java.net.ConnectException) {
            ProbeOutcome(candidate, false, FailureStage.TRANSPORT_REFUSED, null, null, null)
        } catch (e: java.net.SocketException) {
            val stage = if (e.message.orEmpty().contains("reset", true)) {
                FailureStage.TRANSPORT_RESET
            } else {
                FailureStage.TRANSPORT_REFUSED
            }
            ProbeOutcome(candidate, false, stage, null, null, null)
        } catch (_: Exception) {
            ProbeOutcome(candidate, false, FailureStage.UNKNOWN_FAILURE, null, null, null)
        }
    }
}

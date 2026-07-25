package app.dtma.one.bypass

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.dtma.one.core.model.VpnUiState
import app.dtma.one.vpn.DtmaVpnService
import app.dtma.one.vpn.VpnRuntimeStatus
import app.dtma.one.vpn.VpnStateHolder
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-app Cloudflare WARP with:
 * - live ON/OFF/UNHEALTHY detection (stats + tunnel state)
 * - auto-regenerate account when start fails (up to [MAX_AUTO_REGEN] times)
 * - background health monitor + optional auto-reconnect
 */
object WarpController {
    private const val TAG = "DtmaWarp"
    /** Full start cycles (new Cloudflare account each fail). Keep small — user hates long waits. */
    private const val MAX_AUTO_REGEN = 2
    private const val HEALTH_INTERVAL_MS = 3_000L
    private const val STALL_MS = 25_000L
    private const val HANDSHAKE_WAIT_MS = 1_000L
    private const val HANDSHAKE_POLL_MS = 200L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var confCache: WarpConfigGenerator.Result? = null

    private val _status = MutableStateFlow(WarpLiveStatus())
    val status: StateFlow<WarpLiveStatus> = _status.asStateFlow()

    /** User preference: restart with new conf when unhealthy. */
    @Volatile
    var autoReconnect: Boolean = true

    private var monitorJob: Job? = null
    private var lastRx: Long = 0L
    private var lastRxChangeAt: Long = 0L
    private var reconnecting = AtomicBoolean(false)

    val isRunning: Boolean get() = _status.value.mode == WarpMode.ON ||
        _status.value.mode == WarpMode.UNHEALTHY

    fun clearCachedConfig(context: Context? = null) {
        confCache = null
        context?.applicationContext?.let { WarpInstaller.clearPersistentConf(it) }
        publish(
            mode = _status.value.mode,
            message = "Кэш conf сброшен",
            error = _status.value.lastError,
        )
    }

    /**
     * Import WireGuard conf from text (clipboard) — skips Cloudflare API registration.
     * Useful when api.cloudflareclient.com is blocked by the ISP.
     */
    suspend fun startFromConf(context: Context, confText: String): Result<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                stopInternal(keepService = false)
                stopLocalHev(app)
                delay(150)
                try {
                    val parsed = WarpConfigGenerator.fromConfText(confText)
                    confCache = parsed
                    WarpInstaller.savePersistentConf(app, parsed.confText)
                    bringUpOnce(app, attempt = 1, allowRegister = false)
                    startMonitor(app)
                    Result.success(Unit)
                } catch (e: Exception) {
                    val errMsg = shortError(e)
                    publish(
                        mode = WarpMode.ERROR,
                        message = "WARP conf: $errMsg",
                        error = errMsg,
                    )
                    Result.failure(e)
                }
            }
        }

    /**
     * Start WARP. Uses saved conf first; on failure re-registers (if API reachable)
     * up to [MAX_AUTO_REGEN] times.
     */
    suspend fun start(context: Context, forceNewAccount: Boolean = false): Result<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                if (forceNewAccount) {
                    confCache = null
                    WarpInstaller.clearPersistentConf(app)
                } else if (confCache == null) {
                    confCache = loadSaved(app)
                }

                stopInternal(keepService = false)
                stopLocalHev(app)
                delay(200)

                var lastErr: Exception? = null
                var regen = 0
                for (attempt in 1..MAX_AUTO_REGEN) {
                    publish(
                        mode = WarpMode.STARTING,
                        message = "WARP $attempt/$MAX_AUTO_REGEN…",
                        attempts = attempt,
                        autoRegen = regen,
                    )
                    VpnStateHolder.set(
                        VpnRuntimeStatus(
                            state = VpnUiState.STARTING,
                            message = _status.value.message,
                        ),
                    )

                    val outcome = runCatching { bringUpOnce(app, attempt, allowRegister = true) }
                    if (outcome.isSuccess) {
                        startMonitor(app)
                        return@withContext Result.success(Unit)
                    }
                    lastErr = outcome.exceptionOrNull() as? Exception
                        ?: Exception(outcome.exceptionOrNull()?.message ?: "fail")
                    Log.w(TAG, "attempt $attempt failed: ${lastErr.message}")
                    confCache = null
                    WarpInstaller.clearPersistentConf(app)
                    regen++
                    tearDownTunnel()
                    delay(200)
                }

                val errMsg = shortError(lastErr)
                publish(
                    mode = WarpMode.ERROR,
                    message = "WARP ошибка: $errMsg",
                    error = errMsg,
                    attempts = MAX_AUTO_REGEN,
                    autoRegen = regen,
                )
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.ERROR,
                        message = "WARP: $errMsg",
                    ),
                )
                Result.failure(lastErr ?: Exception(errMsg))
            }
        }

    private fun loadSaved(app: Context): WarpConfigGenerator.Result? {
        val text = WarpInstaller.loadPersistentConf(app) ?: return null
        return runCatching { WarpConfigGenerator.fromConfText(text) }
            .onSuccess { Log.i(TAG, "loaded saved conf ep=${it.endpoint}") }
            .onFailure { Log.w(TAG, "saved conf bad: ${it.message}") }
            .getOrNull()
    }

    private fun shortError(e: Exception?): String {
        val m = e?.message ?: "fail"
        return when {
            m.contains("Unable to resolve", true) || m.contains("No address", true) ->
                "DNS: api.cloudflareclient.com. LTE/другой Wi‑Fi / вставьте conf"
            m.contains("Таймаут Cloudflare API", true) ||
                m.contains("timeout", true) || m.contains("timed out", true) ->
                "Таймаут CF API (reg режется). LTE / вставьте conf / 1.1.1.1 app"
            m.contains("handshake", true) || m.contains("rx=0", true) ->
                "UDP CF (2408) не проходит. LTE / 1.1.1.1 app"
            m.contains("HTTP", true) -> m.take(80)
            else -> m.take(160)
        }
    }

    suspend fun stop(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            stopInternal(keepService = false)
            try {
                context.applicationContext.startService(
                    Intent(context, WarpVpnService::class.java)
                        .setAction(WarpVpnService.ACTION_STOP),
                )
            } catch (_: Exception) {
            }
            publish(mode = WarpMode.OFF, message = "WARP выключен")
            VpnStateHolder.set(VpnRuntimeStatus(state = VpnUiState.OFF, message = "WARP stopped"))
        }
    }

    /** Force refresh of live stats (for UI button). */
    suspend fun recheck(context: Context) = withContext(Dispatchers.IO) {
        refreshHealth(context.applicationContext, fromUser = true)
    }

    private suspend fun bringUpOnce(app: Context, attempt: Int, allowRegister: Boolean) {
        publish(
            mode = WarpMode.STARTING,
            message = if (confCache != null) {
                "WARP: сохранённый conf…"
            } else {
                "WARP: регистрация Cloudflare…"
            },
            attempts = attempt,
        )
        val generated = confCache ?: run {
            if (!allowRegister) error("Нет conf и регистрация запрещена")
            WarpConfigGenerator.generate(app).also {
                confCache = it
                WarpInstaller.savePersistentConf(app, it.confText)
            }
        }

        ContextCompat.startForegroundService(
            app,
            Intent(app, WarpVpnService::class.java).setAction(WarpVpnService.ACTION_START),
        )
        var wait = 0
        while (wait < 25 && !WarpVpnService.isAlive) {
            delay(80)
            wait++
        }
        if (!WarpVpnService.isAlive) error("WarpVpnService не стартовал")
        delay(100)

        val be = backend ?: GoBackend(app).also { backend = it }
        val endpoints = generated.endpointCandidates.ifEmpty { listOf(generated.endpoint) }.take(3)
        var lastTx = 0L
        var lastEp = generated.endpoint

        for ((idx, ep) in endpoints.withIndex()) {
            lastEp = ep
            val variant = WarpConfigGenerator.withEndpoint(generated, ep)
            val config = Config.parse(
                ByteArrayInputStream(variant.confText.toByteArray(Charsets.UTF_8)),
            )
            try {
                be.setState(WarpTunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            }

            publish(
                mode = WarpMode.STARTING,
                message = "WARP: $ep (${idx + 1}/${endpoints.size})",
                endpoint = ep,
                address = generated.addressV4,
                attempts = attempt,
            )
            be.setState(WarpTunnel, Tunnel.State.UP, config)

            val deadline = System.currentTimeMillis() + HANDSHAKE_WAIT_MS
            var rx = 0L
            var tx = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(HANDSHAKE_POLL_MS)
                try {
                    val s = be.getStatistics(WarpTunnel)
                    rx = s.totalRx()
                    tx = s.totalTx()
                    lastTx = tx
                    if (rx > 0) {
                        lastRx = rx
                        lastRxChangeAt = System.currentTimeMillis()
                        confCache = variant
                        WarpInstaller.savePersistentConf(app, variant.confText)
                        publish(
                            mode = WarpMode.ON,
                            message = "WARP ВКЛ · $ep",
                            endpoint = ep,
                            address = generated.addressV4,
                            rx = rx,
                            tx = tx,
                            attempts = attempt,
                        )
                        VpnStateHolder.set(
                            VpnRuntimeStatus(
                                state = VpnUiState.ACTIVE,
                                message = "WARP ВКЛ · $ep · ↓${WarpLiveStatus.formatBytes(rx)}",
                                ipv4 = true,
                                ipv6 = false,
                                limitedMode = false,
                            ),
                        )
                        Log.i(TAG, "WARP ON ep=$ep rx=$rx")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stats: ${e.message}")
                }
            }
            Log.w(TAG, "no handshake $ep tx=$tx")
        }

        error("handshake fail (tx=$lastTx last=$lastEp)")
    }

    private fun startMonitor(app: Context) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                try {
                    refreshHealth(app, fromUser = false)
                } catch (e: Exception) {
                    Log.w(TAG, "monitor: ${e.message}")
                }
            }
        }
    }

    private suspend fun refreshHealth(app: Context, fromUser: Boolean) {
        val be = backend
        val mode = _status.value.mode
        if (be == null || (mode != WarpMode.ON && mode != WarpMode.UNHEALTHY && !fromUser)) {
            if (fromUser) {
                publish(
                    mode = WarpMode.OFF,
                    message = "WARP выключен (нечего проверять)",
                )
            }
            return
        }

        val tunnelState = try {
            be.getState(WarpTunnel)
        } catch (_: Exception) {
            Tunnel.State.DOWN
        }

        var rx = 0L
        var tx = 0L
        try {
            val s = be.getStatistics(WarpTunnel)
            rx = s.totalRx()
            tx = s.totalTx()
        } catch (_: Exception) {
        }

        if (rx > lastRx) {
            lastRx = rx
            lastRxChangeAt = System.currentTimeMillis()
        }

        when {
            tunnelState != Tunnel.State.UP -> {
                publish(
                    mode = WarpMode.OFF,
                    message = "WARP ВЫКЛ (tunnel=$tunnelState)",
                    rx = rx,
                    tx = tx,
                    error = _status.value.lastError,
                )
                VpnStateHolder.update {
                    if (it.message.contains("WARP", true)) {
                        it.copy(state = VpnUiState.OFF, message = "WARP off")
                    } else {
                        it
                    }
                }
            }
            rx == 0L && tx > 0L && System.currentTimeMillis() - lastRxChangeAt > 5_000 -> {
                // just came up without rx — still starting
                publish(
                    mode = WarpMode.STARTING,
                    message = "WARP: ждём peer…",
                    rx = rx,
                    tx = tx,
                )
            }
            System.currentTimeMillis() - lastRxChangeAt > STALL_MS && mode == WarpMode.ON -> {
                publish(
                    mode = WarpMode.UNHEALTHY,
                    message = "WARP БОЛЬНОЙ: нет входящего трафика ${STALL_MS / 1000}с",
                    rx = rx,
                    tx = tx,
                    error = "peer stall",
                )
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.UNSTABLE,
                        message = "WARP unhealthy · no RX growth",
                        ipv4 = true,
                    ),
                )
                if (autoReconnect && reconnecting.compareAndSet(false, true)) {
                    Log.w(TAG, "auto-reconnect after stall")
                    scope.launch {
                        try {
                            start(app, forceNewAccount = true)
                        } finally {
                            reconnecting.set(false)
                        }
                    }
                }
            }
            else -> {
                publish(
                    mode = WarpMode.ON,
                    message = "WARP ВКЛ · ${_status.value.endpoint ?: "?"}",
                    rx = rx,
                    tx = tx,
                )
                if (fromUser || mode != WarpMode.ON) {
                    VpnStateHolder.set(
                        VpnRuntimeStatus(
                            state = VpnUiState.ACTIVE,
                            message = "WARP ВКЛ · ↓${WarpLiveStatus.formatBytes(rx)} ↑${WarpLiveStatus.formatBytes(tx)}",
                            ipv4 = true,
                        ),
                    )
                }
            }
        }
    }

    private fun stopInternal(keepService: Boolean) {
        monitorJob?.cancel()
        monitorJob = null
        tearDownTunnel()
        if (!keepService) {
            // service stopped by caller
        }
        lastRx = 0
        lastRxChangeAt = 0
    }

    private fun tearDownTunnel() {
        try {
            backend?.setState(WarpTunnel, Tunnel.State.DOWN, null)
        } catch (_: Exception) {
        }
    }

    private fun stopLocalHev(app: Context) {
        try {
            app.startService(
                Intent(app, DtmaVpnService::class.java).setAction(DtmaVpnService.ACTION_STOP),
            )
        } catch (_: Exception) {
        }
    }

    private fun publish(
        mode: WarpMode,
        message: String,
        endpoint: String? = confCache?.endpoint ?: _status.value.endpoint,
        address: String? = confCache?.addressV4 ?: _status.value.address,
        rx: Long = _status.value.rxBytes,
        tx: Long = _status.value.txBytes,
        error: String? = if (mode == WarpMode.ERROR || mode == WarpMode.UNHEALTHY) {
            _status.value.lastError
        } else {
            null
        },
        attempts: Int = _status.value.startAttempts,
        autoRegen: Int = _status.value.autoRegenUsed,
    ) {
        _status.value = WarpLiveStatus(
            mode = mode,
            message = message,
            endpoint = endpoint,
            address = address,
            rxBytes = rx,
            txBytes = tx,
            lastError = error,
            startAttempts = attempts,
            autoRegenUsed = autoRegen,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun statusLine(): String {
        val s = _status.value
        return "${s.modeLabelRu()} · ${s.trafficLine()} · ep=${s.endpoint ?: "—"} · " +
            "regen=${s.autoRegenUsed} · ${s.message}"
    }
}

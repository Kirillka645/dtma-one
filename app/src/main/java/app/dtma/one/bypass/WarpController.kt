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
    private const val MAX_AUTO_REGEN = 3
    private const val HEALTH_INTERVAL_MS = 2_500L
    /** If ON but no RX growth for this long while TX grows → unhealthy. */
    private const val STALL_MS = 20_000L

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

    fun clearCachedConfig() {
        confCache = null
        publish(
            mode = _status.value.mode,
            message = "Кэш conf сброшен",
            error = _status.value.lastError,
        )
    }

    /**
     * Start WARP. On failure: drop conf, register a new Cloudflare account, retry
     * up to [MAX_AUTO_REGEN] times automatically.
     */
    suspend fun start(context: Context, forceNewAccount: Boolean = false): Result<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                if (forceNewAccount) confCache = null

                stopInternal(keepService = false)
                stopLocalHev(app)
                delay(400)

                var lastErr: Exception? = null
                var regen = 0
                for (attempt in 1..MAX_AUTO_REGEN) {
                    publish(
                        mode = WarpMode.STARTING,
                        message = if (attempt == 1) {
                            "WARP: регистрация / запуск (попытка $attempt/$MAX_AUTO_REGEN)…"
                        } else {
                            "WARP: conf не встал — новый аккаунт Cloudflare ($attempt/$MAX_AUTO_REGEN)…"
                        },
                        attempts = attempt,
                        autoRegen = regen,
                    )
                    VpnStateHolder.set(
                        VpnRuntimeStatus(
                            state = VpnUiState.STARTING,
                            message = _status.value.message,
                        ),
                    )

                    val outcome = runCatching { bringUpOnce(app, attempt) }
                    if (outcome.isSuccess) {
                        startMonitor(app)
                        return@withContext Result.success(Unit)
                    }
                    lastErr = outcome.exceptionOrNull() as? Exception
                        ?: Exception(outcome.exceptionOrNull()?.message ?: "fail")
                    Log.w(TAG, "attempt $attempt failed: ${lastErr.message}")
                    confCache = null
                    regen++
                    tearDownTunnel()
                    delay(600)
                }

                publish(
                    mode = WarpMode.ERROR,
                    message = "WARP не поднялся после $MAX_AUTO_REGEN попыток",
                    error = lastErr?.message,
                    attempts = MAX_AUTO_REGEN,
                    autoRegen = regen,
                )
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.ERROR,
                        message = "WARP: ${lastErr?.message ?: "fail"}",
                    ),
                )
                Result.failure(lastErr ?: Exception("WARP failed"))
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

    private suspend fun bringUpOnce(app: Context, attempt: Int) {
        val generated = confCache ?: WarpConfigGenerator.generate().also {
            confCache = it
            WarpInstaller.writeConf(app, it.confText)
        }
        Log.i(TAG, "attempt=$attempt endpoint=${generated.endpoint} addr=${generated.addressV4}")

        val config = Config.parse(
            ByteArrayInputStream(generated.confText.toByteArray(Charsets.UTF_8)),
        )

        ContextCompat.startForegroundService(
            app,
            Intent(app, WarpVpnService::class.java).setAction(WarpVpnService.ACTION_START),
        )
        var wait = 0
        while (wait < 50 && !WarpVpnService.isAlive) {
            delay(100)
            wait++
        }
        if (!WarpVpnService.isAlive) error("WarpVpnService не стартовал")
        delay(200)

        val be = backend ?: GoBackend(app).also { backend = it }
        val st = be.setState(WarpTunnel, Tunnel.State.UP, config)
        Log.i(TAG, "setState → $st")

        publish(
            mode = WarpMode.STARTING,
            message = "WARP: handshake Cloudflare…",
            endpoint = generated.endpoint,
            address = generated.addressV4,
            attempts = attempt,
        )

        var rx = 0L
        var tx = 0L
        repeat(10) { i ->
            delay(400)
            try {
                val s = be.getStatistics(WarpTunnel)
                rx = s.totalRx()
                tx = s.totalTx()
                Log.i(TAG, "stats[$i] rx=$rx tx=$tx")
                if (rx > 0) return@repeat
            } catch (e: Exception) {
                Log.w(TAG, "stats: ${e.message}")
            }
        }

        if (rx == 0L) {
            error(
                "Нет handshake (rx=0). UDP 2408? endpoint=${generated.endpoint} tx=$tx",
            )
        }

        lastRx = rx
        lastRxChangeAt = System.currentTimeMillis()
        publish(
            mode = WarpMode.ON,
            message = "WARP ВКЛ · ${generated.endpoint}",
            endpoint = generated.endpoint,
            address = generated.addressV4,
            rx = rx,
            tx = tx,
            attempts = attempt,
        )
        VpnStateHolder.set(
            VpnRuntimeStatus(
                state = VpnUiState.ACTIVE,
                message = "WARP ВКЛ · CF ${generated.endpoint} · ↓${WarpLiveStatus.formatBytes(rx)}",
                ipv4 = true,
                ipv6 = false,
                limitedMode = false,
            ),
        )
        Log.i(TAG, "WARP ON rx=$rx")
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

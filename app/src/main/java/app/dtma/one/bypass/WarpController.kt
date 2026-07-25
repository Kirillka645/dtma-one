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
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-app free Cloudflare WARP via embedded WireGuard Go backend.
 *
 * Fixes vs earlier build:
 * - WireGuard [KeyPair] (not BouncyCastle)
 * - IPv4-only tunnel + numeric endpoint
 * - Wait for real peer RX (handshake) before claiming success
 * - Drop bad cached conf on failure
 */
object WarpController {
    private const val TAG = "DtmaWarp"

    private val mutex = Mutex()
    private val running = AtomicBoolean(false)

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var confCache: WarpConfigGenerator.Result? = null

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = running.get()

    suspend fun start(context: Context): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext
                lastError = null

                // Stop local hev path — only one VpnService at a time.
                try {
                    app.startService(
                        Intent(app, DtmaVpnService::class.java)
                            .setAction(DtmaVpnService.ACTION_STOP),
                    )
                } catch (_: Exception) {
                }
                // Tear down previous WARP cleanly
                try {
                    backend?.setState(WarpTunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
                delay(500)

                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.STARTING,
                        message = "WARP: регистрация Cloudflare…",
                    ),
                )

                val generated = confCache ?: WarpConfigGenerator.generate().also {
                    confCache = it
                    WarpInstaller.writeConf(app, it.confText)
                }
                Log.i(TAG, "using conf endpoint=${generated.endpoint} addr=${generated.addressV4}")

                val config = Config.parse(ByteArrayInputStream(generated.confText.toByteArray(Charsets.UTF_8)))

                ContextCompat.startForegroundService(
                    app,
                    Intent(app, WarpVpnService::class.java).setAction(WarpVpnService.ACTION_START),
                )

                var attempts = 0
                while (attempts < 50 && !WarpVpnService.isAlive) {
                    delay(100)
                    attempts++
                }
                if (!WarpVpnService.isAlive) {
                    error("WarpVpnService не стартовал (foreground?)")
                }
                // Let onCreate complete GoBackend future
                delay(200)

                val be = backend ?: GoBackend(app).also { backend = it }
                val state = be.setState(WarpTunnel, Tunnel.State.UP, config)
                Log.i(TAG, "setState → $state")

                // Handshake check: Cloudflare must reply (rx > 0).
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.STARTING,
                        message = "WARP: handshake с Cloudflare…",
                    ),
                )
                var rx = 0L
                var tx = 0L
                repeat(8) { i ->
                    delay(500)
                    try {
                        val st = be.getStatistics(WarpTunnel)
                        rx = st.totalRx()
                        tx = st.totalTx()
                        Log.i(TAG, "stats[$i] rx=$rx tx=$tx")
                        if (rx > 0) return@repeat
                    } catch (e: Exception) {
                        Log.w(TAG, "stats: ${e.message}")
                    }
                }

                if (rx == 0L) {
                    // Peer silent — config/UDP blocked or bad account
                    try {
                        be.setState(WarpTunnel, Tunnel.State.DOWN, null)
                    } catch (_: Exception) {
                    }
                    confCache = null
                    running.set(false)
                    error(
                        "Нет ответа от Cloudflare (UDP handshake). " +
                            "Часто режут порт 2408. Попробуйте «Новый аккаунт» или LTE. " +
                            "tx=$tx rx=0 endpoint=${generated.endpoint}",
                    )
                }

                running.set(true)
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.ACTIVE,
                        message = "WARP OK · CF ${generated.endpoint} · rx=$rx",
                        ipv4 = true,
                        ipv6 = false,
                        limitedMode = false,
                    ),
                )
                Log.i(TAG, "WARP tunnel UP and peer answered rx=$rx")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "WARP start failed", e)
                lastError = e.message
                running.set(false)
                confCache = null
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.ERROR,
                        message = "WARP: ${e.message ?: "start failed"}",
                    ),
                )
                try {
                    backend?.setState(WarpTunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
                try {
                    context.applicationContext.startService(
                        Intent(context, WarpVpnService::class.java)
                            .setAction(WarpVpnService.ACTION_STOP),
                    )
                } catch (_: Exception) {
                }
                Result.failure(e)
            }
        }
    }

    suspend fun stop(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                backend?.setState(WarpTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "setState DOWN: ${e.message}")
            }
            running.set(false)
            try {
                context.applicationContext.startService(
                    Intent(context, WarpVpnService::class.java)
                        .setAction(WarpVpnService.ACTION_STOP),
                )
            } catch (_: Exception) {
            }
            VpnStateHolder.set(VpnRuntimeStatus(state = VpnUiState.OFF, message = "WARP stopped"))
            Log.i(TAG, "WARP tunnel DOWN")
        }
    }

    fun clearCachedConfig() {
        confCache = null
    }

    /** Debug dump of current conf (no private key in UI). */
    fun statusLine(): String {
        val c = confCache
        return if (running.get()) {
            "running endpoint=${c?.endpoint} addr=${c?.addressV4}"
        } else {
            "stopped lastError=${lastError ?: "—"}"
        }
    }
}

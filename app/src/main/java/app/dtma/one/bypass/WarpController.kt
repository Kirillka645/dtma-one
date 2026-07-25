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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs free Cloudflare WARP **inside DTMA** via embedded WireGuard Go backend.
 * No external WireGuard app required.
 */
object WarpController {
    private const val TAG = "DtmaWarp"

    private val mutex = Mutex()
    private val running = AtomicBoolean(false)

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var confCache: String? = null

    val isRunning: Boolean get() = running.get()

    suspend fun start(context: Context): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext

                // Avoid double VPN: stop local hev path first.
                try {
                    app.startService(
                        Intent(app, DtmaVpnService::class.java)
                            .setAction(DtmaVpnService.ACTION_STOP),
                    )
                } catch (_: Exception) {
                }
                delay(400)

                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.STARTING,
                        message = "WARP: registering / starting…",
                    ),
                )

                val confText = confCache ?: WarpConfigGenerator.generate().confText.also {
                    confCache = it
                    WarpInstaller.writeConf(app, it)
                }

                val config = Config.parse(ByteArrayInputStream(confText.toByteArray(Charsets.UTF_8)))

                // Bring up GoBackend.VpnService (required by wireguard-android).
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, WarpVpnService::class.java).setAction(WarpVpnService.ACTION_START),
                )

                // Wait until service is bound / onCreate completed.
                var attempts = 0
                while (attempts < 40 && !WarpVpnService.isAlive) {
                    delay(100)
                    attempts++
                }
                if (!WarpVpnService.isAlive) {
                    error("WarpVpnService did not start")
                }

                val be = backend ?: GoBackend(app).also { backend = it }
                be.setState(WarpTunnel, Tunnel.State.UP, config)
                running.set(true)

                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.ACTIVE,
                        message = "Cloudflare WARP (in-app) · YouTube/TG via CF",
                        ipv4 = true,
                        ipv6 = true,
                        limitedMode = false,
                    ),
                )
                Log.i(TAG, "WARP tunnel UP")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "WARP start failed", e)
                running.set(false)
                VpnStateHolder.set(
                    VpnRuntimeStatus(
                        state = VpnUiState.ERROR,
                        message = "WARP: ${e.message ?: "start failed"}",
                    ),
                )
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

    /** Force new Cloudflare registration next start. */
    fun clearCachedConfig() {
        confCache = null
    }
}

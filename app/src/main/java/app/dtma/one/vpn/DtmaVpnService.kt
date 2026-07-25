package app.dtma.one.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import app.dtma.one.DtmaApp
import app.dtma.one.MainActivity
import app.dtma.one.R
import app.dtma.one.core.model.VpnUiState
import app.dtma.one.core.network.NetworkContextFactory
import app.dtma.one.core.storage.UserSettings
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Local VpnService: hev-socks5-tunnel (lwIP) + egress SOCKS5.
 *
 * Egress modes:
 * 1) Default — local SOCKS5 with [protect] (same ISP, no remote infra).
 * 2) Optional user-provided upstream SOCKS5 (YOUR proxy — not DTMA servers).
 *    Use this when probe shows Telegram DCs blocked on the ISP path.
 */
class DtmaVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var socks5: LocalSocks5Server? = null
    private var hevRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (hevRunning || tun != null) return

        VpnStateHolder.update {
            it.copy(state = VpnUiState.STARTING, message = "Starting local tunnel…")
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            if (!HevTunnel.available) {
                throw IllegalStateException(
                    "Native tun2socks library not loaded (hev-socks5-tunnel). Rebuild with NDK.",
                )
            }

            val settings: UserSettings = runBlocking {
                DtmaApp.instance.settingsRepository.settings.first()
            }
            val networkContext = NetworkContextFactory.current(this)

            val socksHost: String
            val socksPort: Int
            val modeLabel: String

            if (settings.hasUpstreamSocks()) {
                // User-provided remote SOCKS5 (e.g. VPS that can reach Telegram DCs).
                socks5?.stop()
                socks5 = null
                socksHost = settings.upstreamSocksHost.trim()
                socksPort = settings.upstreamSocksPort
                modeLabel = "Upstream SOCKS5 $socksHost:$socksPort (ваш прокси)"
                Log.i(TAG, "Using user upstream SOCKS5 $socksHost:$socksPort")
            } else {
                val socks = LocalSocks5Server(this, bindPort = 18080)
                socks.start()
                socks5 = socks
                socksHost = "127.0.0.1"
                socksPort = socks.listenPort
                modeLabel = "Local SOCKS5 (тот же ISP; blocked DC не откроет)"
            }

            val builder = Builder()
                .setSession("DTMA One")
                .setMtu(1400)
                .addAddress("10.0.0.2", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            var ipv6 = false
            try {
                builder.addAddress("fd00:646d:7461::2", 64)
                builder.addRoute("::", 0)
                ipv6 = true
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 TUN not enabled: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "disallow self: ${e.message}")
            }
            // If user SOCKS is a local IP on LAN, also fine (disallowed app reaches it).

            val pfd = builder.establish()
            if (pfd == null) {
                VpnStateHolder.update {
                    it.copy(
                        state = VpnUiState.ERROR,
                        message = "VPN permission missing or establish() failed",
                    )
                }
                socks5?.stop()
                socks5 = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            tun = pfd

            val configFile = writeHevConfig(socksHost, socksPort, ipv6)
            Log.i(TAG, "hev start fd=${pfd.fd} socks=$socksHost:$socksPort ipv6=$ipv6")
            HevTunnel.TProxyStartService(configFile.absolutePath, pfd.fd)
            hevRunning = true

            VpnStateHolder.set(
                VpnRuntimeStatus(
                    state = VpnUiState.ACTIVE,
                    message = modeLabel,
                    flowCount = 0,
                    networkContext = networkContext,
                    ipv4 = true,
                    ipv6 = ipv6,
                    limitedMode = !settings.hasUpstreamSocks(),
                ),
            )
            Log.i(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            VpnStateHolder.update {
                it.copy(state = VpnUiState.ERROR, message = e.message ?: "start failed")
            }
            stopVpn()
        }
    }

    private fun writeHevConfig(socksHost: String, socksPort: Int, ipv6: Boolean): File {
        val f = File(filesDir, "hev-config.yml")
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 1400")
            appendLine("  multi-queue: false")
            appendLine("  ipv4: 10.0.0.2")
            if (ipv6) appendLine("  ipv6: 'fd00:646d:7461::2'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: $socksHost")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  log-level: warn")
            appendLine("  connect-timeout: 15000")
            appendLine("  tcp-read-write-timeout: 600000")
            appendLine("  udp-read-write-timeout: 120000")
            appendLine("  max-session-count: 0")
        }
        f.writeText(yaml)
        return f
    }

    private fun stopVpn() {
        try {
            if (hevRunning && HevTunnel.available) {
                HevTunnel.TProxyStopService()
            }
        } catch (e: Exception) {
            Log.w(TAG, "hev stop: ${e.message}")
        }
        hevRunning = false
        try {
            socks5?.stop()
        } catch (_: Exception) {
        }
        socks5 = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        VpnStateHolder.set(VpnRuntimeStatus(state = VpnUiState.OFF, message = "Stopped"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (hevRunning || tun != null) stopVpn()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DtmaVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DtmaApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vpn_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "DtmaVpnService"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "app.dtma.one.vpn.STOP"
        const val ACTION_START = "app.dtma.one.vpn.START"
    }
}

package app.dtma.one.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
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
 * Local VpnService: hev-socks5-tunnel (lwIP) + SOCKS5 egress.
 *
 * Default: local protect SOCKS5 (same ISP).
 * Optional: user upstream SOCKS5 when Telegram/other DCs are ISP-blocked.
 *
 * Does NOT use legacy TunDataplane / selectDestination remap.
 */
class DtmaVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var socks5: LocalSocks5Server? = null
    private var hevRunning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            val underlying = UnderlyingNetwork.current(this)

            val socksHost: String
            val socksPort: Int
            val modeLabel: String

            if (settings.hasUpstreamSocks()) {
                socks5?.stop()
                socks5 = null
                socksHost = settings.upstreamSocksHost.trim()
                socksPort = settings.upstreamSocksPort
                modeLabel = "Upstream SOCKS5 $socksHost:$socksPort (ваш прокси)"
                Log.i(TAG, "User upstream SOCKS5 $socksHost:$socksPort")
            } else {
                val multipath = settings.telegramMultipath
                val socks = LocalSocks5Server(
                    vpn = this,
                    bindPort = 18080,
                    telegramMultipath = multipath,
                )
                socks.start()
                socks5 = socks
                socksHost = "127.0.0.1"
                socksPort = socks.listenPort
                modeLabel = if (multipath) {
                    "Local · multipath Telegram (Wi‑Fi/LTE without SOCKS5)"
                } else {
                    "Local SOCKS5 · same ISP"
                }
            }

            val builder = Builder()
                .setSession("DTMA One")
                .setMtu(1400)
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)

            // Prefer underlying network DNS; fall back to public only if empty.
            val dnsList = underlying.dnsServers.mapNotNull { it.hostAddress }.distinct()
            if (dnsList.isNotEmpty()) {
                dnsList.take(3).forEach { builder.addDnsServer(it) }
                Log.i(TAG, "DNS from underlying: $dnsList")
            } else {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                Log.w(TAG, "No underlying DNS; using 1.1.1.1/8.8.8.8 fallback")
            }

            var ipv6 = false
            try {
                builder.addAddress("fd00:646d:7461::2", 64)
                builder.addRoute("::", 0)
                ipv6 = true
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 TUN not enabled: ${e.message}")
            }

            // Inherit metered status (do not force unmetered — avoids surprise media auto-download).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(underlying.metered)
            }

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "disallow self: ${e.message}")
            }

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

            // Bind VPN to physical network so protected/disallowed traffic tracks Wi‑Fi↔LTE.
            if (underlying.network != null) {
                try {
                    setUnderlyingNetworks(arrayOf(underlying.network))
                } catch (e: Exception) {
                    Log.w(TAG, "setUnderlyingNetworks: ${e.message}")
                }
            }
            registerNetworkWatcher()

            val configFile = writeHevConfig(socksHost, socksPort, ipv6)
            Log.i(TAG, "hev start fd=${pfd.fd} socks=$socksHost:$socksPort ipv6=$ipv6 metered=${underlying.metered}")
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
            Log.i(TAG, "VPN started (hev, not TunDataplane)")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            VpnStateHolder.update {
                it.copy(state = VpnUiState.ERROR, message = e.message ?: "start failed")
            }
            stopVpn()
        }
    }

    private fun registerNetworkWatcher() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        unregisterNetworkWatcher()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    setUnderlyingNetworks(arrayOf(network))
                    Log.i(TAG, "underlying network available: $network")
                } catch (e: Exception) {
                    Log.w(TAG, "onAvailable: ${e.message}")
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "underlying network lost: $network")
                val next = UnderlyingNetwork.current(this@DtmaVpnService).network
                try {
                    setUnderlyingNetworks(if (next != null) arrayOf(next) else null)
                } catch (e: Exception) {
                    Log.w(TAG, "onLost setUnderlying: ${e.message}")
                }
                VpnStateHolder.update {
                    it.copy(
                        state = if (next != null) VpnUiState.ACTIVE else VpnUiState.UNSTABLE,
                        message = if (next != null) {
                            it.message
                        } else {
                            "Underlying network lost — waiting…"
                        },
                    )
                }
            }
        }
        networkCallback = cb
        UnderlyingNetwork.requestNonVpn(cm, cb)
    }

    private fun unregisterNetworkWatcher() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
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
        unregisterNetworkWatcher()
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

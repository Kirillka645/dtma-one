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
import java.io.File

/**
 * Local-only VpnService using:
 * 1) TUN from [Builder.establish]
 * 2) hev-socks5-tunnel (lwIP userspace stack) as tun2socks
 * 3) [LocalSocks5Server] with [protect] for real outbound sockets
 *
 * No remote VPN/proxy infrastructure of the project authors.
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

            val networkContext = NetworkContextFactory.current(this)

            // Local SOCKS5 first (outbound with protect / app-disallow).
            val socks = LocalSocks5Server(this, bindPort = 18080)
            socks.start()
            socks5 = socks
            val socksPort = socks.listenPort

            val builder = Builder()
                .setSession("DTMA One")
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            // Do not claim IPv6 until dual-stack is validated end-to-end.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            // Exclude ourselves so SOCKS5 + hev control sockets never re-enter TUN.
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
                socks.stop()
                socks5 = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            tun = pfd

            val configFile = writeHevConfig(socksPort)
            val fd = pfd.fd
            Log.i(TAG, "Starting hev tun2socks fd=$fd socks=127.0.0.1:$socksPort cfg=${configFile.absolutePath}")

            // Blocks in native thread inside hev; returns immediately from JNI after spawn.
            HevTunnel.TProxyStartService(configFile.absolutePath, fd)
            hevRunning = true

            VpnStateHolder.set(
                VpnRuntimeStatus(
                    state = VpnUiState.ACTIVE,
                    message = "Local tun2socks (hev+SOCKS5). No remote server. IPv4.",
                    flowCount = 0,
                    networkContext = networkContext,
                    ipv4 = true,
                    ipv6 = false,
                    limitedMode = false,
                ),
            )
            Log.i(TAG, "VPN started (hev)")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            VpnStateHolder.update {
                it.copy(state = VpnUiState.ERROR, message = e.message ?: "start failed")
            }
            stopVpn()
        }
    }

    private fun writeHevConfig(socksPort: Int): File {
        val f = File(filesDir, "hev-config.yml")
        // hev owns the TUN stack; socks5 is our protect()'d egress.
        val yaml = """
            |tunnel:
            |  mtu: 1500
            |  multi-queue: false
            |  ipv4: 10.0.0.2
            |socks5:
            |  port: $socksPort
            |  address: 127.0.0.1
            |  udp: 'udp'
            |misc:
            |  log-level: warn
            |  connect-timeout: 10000
            |  tcp-read-write-timeout: 300000
            |  udp-read-write-timeout: 60000
            |
        """.trimMargin()
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

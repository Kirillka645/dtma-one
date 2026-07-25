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
import app.dtma.one.core.network.dns.ProtectedDnsClient
import app.dtma.one.core.network.tun.DnsSessionCache
import app.dtma.one.core.network.tun.SimpleDnsServer
import app.dtma.one.core.network.tun.TunDataplane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Local-only VpnService. No remote VPN server, proxy, or developer infrastructure.
 * Establishes TUN and runs real userspace dataplane with protect().
 */
class DtmaVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var dataplane: TunDataplane? = null
    private val sessionCache = DnsSessionCache()

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
        if (dataplane != null) return
        VpnStateHolder.update {
            it.copy(state = VpnUiState.STARTING, message = "Establishing TUN…")
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val networkContext = NetworkContextFactory.current(this)
            // IPv4-only VPN path for MVP.
            // Claiming IPv6 (::/0) without a working IPv6 userspace stack black-holes apps
            // that prefer AAAA (Telegram often does). Leave IPv6 on the physical network.
            val builder = Builder()
                .setSession("DTMA One")
                .setMtu(1500)
                // /24 so 10.0.0.1 (VPN DNS) is on-link with our TUN address.
                .addAddress("10.0.0.2", 24)
                .addDnsServer("10.0.0.1")
                .addRoute("0.0.0.0", 0)

            val ipv6Enabled = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            // Blocking TUN fd: reader thread blocks until packets arrive.
            builder.setBlocking(true)

            // Do not allow apps to bypass VPN when the platform supports it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Prefer all apps through VPN; no per-app disallow list in MVP.
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication self: ${e.message}")
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                VpnStateHolder.update {
                    it.copy(
                        state = VpnUiState.ERROR,
                        message = "VPN permission revoked or establish() failed",
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            tun = pfd

            val dns = SimpleDnsServer(
                sessionCache = sessionCache,
                networkContextId = networkContext.id,
                protectedDns = ProtectedDnsClient(vpnService = this),
                rvecProvider = { host ->
                    runBlocking {
                        DtmaApp.instance.rvecStore.listForHost(host)
                    }
                },
                onResolved = { host, candidates ->
                    Log.d(TAG, "DNS $host -> ${candidates.map { it.ipAddress }}")
                },
            )

            val plane = TunDataplane(
                vpnService = this,
                tunInterface = pfd,
                dnsServer = dns,
                sessionCache = sessionCache,
                selectDestination = { hostname, originalIp, _ ->
                    // Transparent remap only when hostname known from managed DNS.
                    if (hostname == null) return@TunDataplane originalIp
                    val preferred = sessionCache.ipsForHost(hostname).firstOrNull()
                    preferred ?: originalIp
                },
            )
            plane.onFlowCountChanged = { count ->
                VpnStateHolder.update { it.copy(flowCount = count) }
            }
            plane.start()
            dataplane = plane

            VpnStateHolder.set(
                VpnRuntimeStatus(
                    state = if (ipv6Enabled) VpnUiState.ACTIVE else VpnUiState.ACTIVE,
                    message = "Local dataplane: IPv4 TCP/UDP/DNS + protect(). IPv6 not captured (avoids black-hole).",
                    flowCount = 0,
                    networkContext = networkContext,
                    ipv4 = true,
                    ipv6 = ipv6Enabled,
                    limitedMode = false,
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

    private fun stopVpn() {
        dataplane?.stop()
        dataplane = null
        sessionCache.clear()
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
        scope.cancel()
        dataplane?.stop()
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

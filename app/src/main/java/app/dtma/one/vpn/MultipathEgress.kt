package app.dtma.one.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connect using a *specific* underlying network (e.g. cellular while default is Wi‑Fi).
 *
 * When the ISP blocks Telegram only on one access path (home Wi‑Fi) but not another
 * (mobile data), this can open DC without any SOCKS5/remote proxy.
 *
 * If *every* path is blocked, this still fails — physics, not a bug.
 */
object MultipathEgress {
    private const val TAG = "DtmaMultipath"

    data class Connected(
        val socket: Socket,
        val via: String,
    )

    fun connect(
        context: Context,
        vpn: VpnService,
        dest: InetAddress,
        port: Int,
        timeoutMs: Int = 8_000,
        preferAlternateFirst: Boolean = true,
    ): Connected? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = listCandidateNetworks(cm)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no non-VPN networks for multipath")
            return null
        }

        val ordered = if (preferAlternateFirst) {
            val defaultNet = cm.activeNetwork
            val preferred = candidates.filter { it.network != defaultNet }
            preferred + candidates.filter { it.network == defaultNet }
        } else {
            candidates
        }

        for (c in ordered) {
            val sock = Socket()
            try {
                if (!vpn.protect(sock)) {
                    sock.close()
                    continue
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    c.network.bindSocket(sock)
                }
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.soTimeout = 0
                sock.connect(InetSocketAddress(dest, port), timeoutMs)
                Log.i(TAG, "ok ${dest.hostAddress}:$port via ${c.label}")
                return Connected(sock, c.label)
            } catch (e: Exception) {
                Log.d(TAG, "fail ${dest.hostAddress}:$port via ${c.label}: ${e.message}")
                try {
                    sock.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private data class Candidate(val network: Network, val label: String)

    private fun listCandidateNetworks(cm: ConnectivityManager): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val label = buildString {
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> append("wifi")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> append("cellular")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> append("ethernet")
                    else -> append("net")
                }
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    append("+ok")
                }
            }
            out.add(Candidate(n, label))
        }
        // Prefer cellular for "Wi‑Fi censored" case when listing
        return out.sortedBy { c ->
            when {
                c.label.startsWith("cellular") -> 0
                c.label.startsWith("wifi") -> 1
                else -> 2
            }
        }
    }
}

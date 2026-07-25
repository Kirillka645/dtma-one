package app.dtma.one.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.InetAddress

/**
 * Finds non-VPN underlying network for setUnderlyingNetworks / DNS / metered.
 */
object UnderlyingNetwork {

    data class Info(
        val network: Network?,
        val dnsServers: List<InetAddress>,
        val metered: Boolean,
    )

    fun current(context: Context): Info {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@filter false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@filter false
            true
        }
        // Prefer validated + not captive
        val network = candidates.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()

        val dns = network?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
        val metered = if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        } else {
            cm.isActiveNetworkMetered
        }
        return Info(network, dns.filterNotNull(), metered)
    }

    fun requestNonVpn(cm: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(req, callback)
        } catch (_: Exception) {
            cm.registerDefaultNetworkCallback(callback)
        }
    }
}

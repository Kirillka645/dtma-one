package app.dtma.one.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.dtma.one.core.model.NetworkContext
import app.dtma.one.core.model.NetworkType
import java.util.UUID

object NetworkContextFactory {

    fun current(context: Context): NetworkContext {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network? = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }

        val type = when {
            caps == null -> NetworkType.UNKNOWN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }

        var hasV4 = false
        var hasV6 = false
        link?.linkAddresses?.forEach { la ->
            val host = la.address.hostAddress ?: return@forEach
            if (host.contains(':')) hasV6 = true else hasV4 = true
        }

        val dnsFp = link?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.sorted()
            ?.joinToString(",")
            ?.let { "dns:${it.hashCode()}" }

        return NetworkContext(
            id = UUID.randomUUID().toString(),
            networkType = type,
            androidNetworkId = network?.toString(),
            hasIpv4 = hasV4,
            hasIpv6 = hasV6,
            dnsFingerprint = dnsFp,
            createdAt = System.currentTimeMillis(),
        )
    }
}

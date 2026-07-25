package app.dtma.one.core.network.tun


/**
 * LEGACY / NOT USED by DtmaVpnService (0.2.x uses hev + LocalSocks5).
 * Do not re-enable without fixing docs/AUDIT_CLAUDE_RESPONSE.md checklist.
 */
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps destination IPs that we answered via managed DNS back to hostnames.
 * Used only for transparent PAER context; never invents hostnames.
 */
class DnsSessionCache {
    private val ipToHost = ConcurrentHashMap<String, String>()
    private val hostToIps = ConcurrentHashMap<String, List<String>>()

    fun remember(hostname: String, ips: List<String>) {
        val host = hostname.lowercase()
        hostToIps[host] = ips
        ips.forEach { ipToHost[it] = host }
    }

    fun hostnameForIp(ip: String): String? = ipToHost[ip]

    fun ipsForHost(hostname: String): List<String> = hostToIps[hostname.lowercase()].orEmpty()

    fun clear() {
        ipToHost.clear()
        hostToIps.clear()
    }
}

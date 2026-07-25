package app.dtma.one.core.network.dns

import app.dtma.one.core.model.CandidateSource
import app.dtma.one.core.model.EndpointCandidate
import app.dtma.one.core.model.IpFamily
import app.dtma.one.core.model.Transport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves A/AAAA via platform DNS. Does not scan ports or invent endpoints.
 * HTTPS/SVCB: recorded as NOT fully parsed in MVP when platform does not expose records.
 */
class SystemDnsResolver {

    suspend fun resolve(
        hostname: String,
        port: Int = 443,
        networkContextId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): DnsResolveResult = withContext(Dispatchers.IO) {
        val host = hostname.trim().lowercase().substringBefore('/').substringBefore('?')
        if (host.isEmpty()) {
            return@withContext DnsResolveResult.Empty
        }
        try {
            val addrs = InetAddress.getAllByName(host)
            if (addrs.isEmpty()) return@withContext DnsResolveResult.Empty
            val candidates = addrs.mapNotNull { addr ->
                val ip = addr.hostAddress ?: return@mapNotNull null
                val family = when (addr) {
                    is Inet6Address -> IpFamily.IPV6
                    is Inet4Address -> IpFamily.IPV4
                    else -> if (ip.contains(':')) IpFamily.IPV6 else IpFamily.IPV4
                }
                EndpointCandidate(
                    hostname = host,
                    ipAddress = ip,
                    ipFamily = family,
                    port = port,
                    transport = Transport.TCP,
                    alpn = listOf("h2", "http/1.1"),
                    source = CandidateSource.CURRENT_DNS,
                    networkContextId = networkContextId,
                    discoveredAt = nowMs,
                )
            }
            if (candidates.isEmpty()) DnsResolveResult.Empty else DnsResolveResult.Ok(candidates)
        } catch (_: UnknownHostException) {
            DnsResolveResult.NoResponse
        } catch (_: Exception) {
            DnsResolveResult.NoResponse
        }
    }
}

sealed class DnsResolveResult {
    data class Ok(val candidates: List<EndpointCandidate>) : DnsResolveResult()
    data object Empty : DnsResolveResult()
    data object NoResponse : DnsResolveResult()
}

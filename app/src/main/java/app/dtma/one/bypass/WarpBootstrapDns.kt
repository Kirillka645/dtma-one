package app.dtma.one.bypass

import android.util.Log
import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS for WARP registration when the operator's resolver is broken/poisoned
 * ("Unable to resolve host api.cloudflareclient.com").
 *
 * Strategy (no dependency on system DNS for Cloudflare hosts):
 * 1) Static bootstrap map (last-known / documented anycast)
 * 2) Plain UDP/53 to public resolvers by **IP** (1.1.1.1, 8.8.8.8, …)
 * 3) System DNS as last resort
 */
object WarpBootstrapDns : Dns {
    private const val TAG = "DtmaWarpDns"

    /** Public resolvers as IPs — must not need DNS to reach them. */
    private val bootstrapResolvers = listOf(
        "1.1.1.1",
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "9.9.9.9",
        "208.67.222.222", // OpenDNS
        "94.140.14.14", // AdGuard
        "76.76.2.0", // Control D
    )

    /**
     * Optional static hints (Cloudflare anycast moves; used only as extra candidates).
     * Updated opportunistically when UDP resolve succeeds.
     */
    private val staticHints = ConcurrentHashMap<String, List<String>>()

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_MS = 10 * 60_000L

    init {
        // Seed common WARP / CF API hosts — may go stale; UDP resolve overrides.
        staticHints["api.cloudflareclient.com"] = listOf(
            "104.16.132.229",
            "104.16.133.229",
            "104.18.25.176",
            "104.18.24.176",
        )
        staticHints["engage.cloudflareclient.com"] = listOf(
            "162.159.192.1",
            "162.159.193.1",
            "162.159.195.1",
            "188.114.98.0",
            "188.114.99.0",
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase().trimEnd('.')
        if (host.isEmpty()) return emptyList()

        // Literal IP
        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
            return listOf(InetAddress.getByName(host))
        }

        val now = System.currentTimeMillis()
        cache[host]?.let { (at, list) ->
            if (now - at < CACHE_MS && list.isNotEmpty()) return list
        }

        val found = LinkedHashSet<InetAddress>()

        // 1) UDP to bootstrap resolvers
        for (resolver in bootstrapResolvers) {
            try {
                val addrs = udpQueryA(host, resolver, timeoutMs = 1500)
                if (addrs.isNotEmpty()) {
                    found.addAll(addrs)
                    staticHints[host] = addrs.mapNotNull { it.hostAddress }
                    Log.i(TAG, "UDP $resolver → $host = ${addrs.map { it.hostAddress }}")
                    break
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP $resolver $host: ${e.message}")
            }
        }

        // 2) Static hints
        staticHints[host]?.forEach { ip ->
            runCatching { found.add(InetAddress.getByName(ip)) }
        }

        // 3) System DNS last
        if (found.isEmpty()) {
            try {
                found.addAll(Dns.SYSTEM.lookup(host))
            } catch (e: Exception) {
                Log.w(TAG, "SYSTEM $host: ${e.message}")
            }
        }

        val list = found.toList()
        if (list.isNotEmpty()) {
            cache[host] = now to list
        } else {
            Log.e(TAG, "FAILED resolve $host on all paths")
        }
        return list
    }

    /** Force-refresh resolve (ignore cache). */
    fun resolveFresh(hostname: String): List<InetAddress> {
        cache.remove(hostname.trim().lowercase().trimEnd('.'))
        return lookup(hostname)
    }

    private fun udpQueryA(name: String, resolverIp: String, timeoutMs: Int): List<InetAddress> {
        val query = buildQuery(name, type = 1)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(
                DatagramPacket(
                    query,
                    query.size,
                    InetSocketAddress(resolverIp, 53),
                ),
            )
            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            return parseA(buf, resp.length)
        }
    }

    private fun buildQuery(name: String, type: Int): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        val nameBytes = ArrayList<Byte>()
        for (l in labels) {
            val b = l.toByteArray(Charsets.US_ASCII)
            nameBytes += b.size.toByte()
            nameBytes.addAll(b.toList())
        }
        nameBytes += 0
        val buf = ByteBuffer.allocate(12 + nameBytes.size + 4)
        val id = (System.nanoTime() and 0xFFFF).toInt()
        buf.putShort(id.toShort())
        buf.putShort(0x0100) // RD
        buf.putShort(1) // QD
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(nameBytes.toByteArray())
        buf.putShort(type.toShort())
        buf.putShort(1) // IN
        return buf.array()
    }

    private fun parseA(data: ByteArray, length: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        var i = 12
        while (i < length) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                i += 5
                break
            }
            if (len and 0xC0 == 0xC0) {
                i += 6
                break
            }
            i += 1 + len
        }
        val out = mutableListOf<InetAddress>()
        repeat(anCount) {
            if (i >= length) return@repeat
            if (i < length && (data[i].toInt() and 0xC0) == 0xC0) i += 2
            else {
                while (i < length) {
                    val len = data[i].toInt() and 0xFF
                    if (len == 0) {
                        i++
                        break
                    }
                    if (len and 0xC0 == 0xC0) {
                        i += 2
                        break
                    }
                    i += 1 + len
                }
            }
            if (i + 10 > length) return@repeat
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 8
            val rdlen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (i + rdlen > length) return@repeat
            if (type == 1 && rdlen == 4) {
                runCatching {
                    out += InetAddress.getByAddress(data.copyOfRange(i, i + 4))
                }
            }
            i += rdlen
        }
        return out
    }
}

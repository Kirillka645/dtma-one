package app.dtma.one.bypass

import android.util.Log
import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Fast multi-resolver DNS for Cloudflare hosts when operator DNS fails.
 * Parallel UDP to a few public resolvers (by IP), 1s budget total.
 */
object WarpBootstrapDns : Dns {
    private const val TAG = "DtmaWarpDns"

    private val bootstrapResolvers = listOf(
        "1.1.1.1",
        "8.8.8.8",
        "9.9.9.9",
        "1.0.0.1",
    )

    private val staticHints = ConcurrentHashMap<String, List<String>>()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_MS = 15 * 60_000L

    init {
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
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase().trimEnd('.')
        if (host.isEmpty()) return emptyList()
        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
            return listOf(InetAddress.getByName(host))
        }

        val now = System.currentTimeMillis()
        cache[host]?.let { (at, list) ->
            if (now - at < CACHE_MS && list.isNotEmpty()) return list
        }

        // Parallel UDP — first success wins (max ~1s)
        val resolved = parallelUdpResolve(host, timeoutMs = 1000)
        if (resolved.isNotEmpty()) {
            cache[host] = now to resolved
            staticHints[host] = resolved.mapNotNull { it.hostAddress }
            Log.i(TAG, "ok $host → ${resolved.map { it.hostAddress }}")
            return resolved
        }

        // Static hints (instant) so registration can still attempt TLS
        val hints = staticHints[host].orEmpty().mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        if (hints.isNotEmpty()) {
            Log.w(TAG, "using static hints for $host: ${hints.map { it.hostAddress }}")
            cache[host] = now to hints
            return hints
        }

        return try {
            Dns.SYSTEM.lookup(host).also {
                if (it.isNotEmpty()) cache[host] = now to it
            }
        } catch (e: Exception) {
            Log.e(TAG, "FAILED $host: ${e.message}")
            emptyList()
        }
    }

    fun resolveFresh(hostname: String): List<InetAddress> {
        cache.remove(hostname.trim().lowercase().trimEnd('.'))
        return lookup(hostname)
    }

    private fun parallelUdpResolve(name: String, timeoutMs: Long): List<InetAddress> {
        val winner = AtomicReference<List<InetAddress>?>(null)
        val latch = CountDownLatch(1)
        val threads = bootstrapResolvers.map { resolver ->
            thread(isDaemon = true, name = "warp-dns-$resolver") {
                if (winner.get() != null) return@thread
                try {
                    val addrs = udpQueryA(name, resolver, timeoutMs = timeoutMs.toInt())
                    if (addrs.isNotEmpty() && winner.compareAndSet(null, addrs)) {
                        latch.countDown()
                    }
                } catch (_: Exception) {
                }
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val result = winner.get().orEmpty()
        // don't join forever
        threads.forEach { it.interrupt() }
        return result
    }

    private fun udpQueryA(name: String, resolverIp: String, timeoutMs: Int): List<InetAddress> {
        val query = buildQuery(name, type = 1)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.coerceIn(400, 2000)
            socket.send(
                DatagramPacket(query, query.size, InetSocketAddress(resolverIp, 53)),
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
        buf.putShort((System.nanoTime() and 0xFFFF).toShort())
        buf.putShort(0x0100)
        buf.putShort(1)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(nameBytes.toByteArray())
        buf.putShort(type.toShort())
        buf.putShort(1)
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

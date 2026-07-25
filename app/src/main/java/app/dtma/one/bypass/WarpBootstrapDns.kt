package app.dtma.one.bypass

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
 * Resolve Cloudflare hosts without operator DNS.
 * 1) cache  2) parallel UDP/53 to public resolvers by IP  3) DoH HTTPS to 1.1.1.1/8.8.8.8
 * 4) static hints  5) system
 */
object WarpBootstrapDns : Dns {
    private const val TAG = "DtmaWarpDns"

    private val bootstrapResolvers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "1.0.0.1")

    private val staticHints = ConcurrentHashMap<String, List<String>>()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_MS = 15 * 60_000L

    private val dohHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    init {
        staticHints["api.cloudflareclient.com"] = listOf(
            "104.16.132.229",
            "104.16.133.229",
            "104.18.25.176",
            "104.18.24.176",
            "104.19.192.29",
            "104.19.193.29",
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

        // Parallel UDP first (≤800ms)
        val udp = parallelUdpResolve(host, timeoutMs = 800)
        if (udp.isNotEmpty()) {
            return remember(host, udp)
        }

        // DoH over HTTPS to resolver IPs (works when UDP/53 blocked, TCP/443 ok)
        val doh = dohResolve(host)
        if (doh.isNotEmpty()) {
            return remember(host, doh)
        }

        val hints = staticHints[host].orEmpty().mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        if (hints.isNotEmpty()) {
            Log.w(TAG, "static hints $host → ${hints.map { it.hostAddress }}")
            return remember(host, hints)
        }

        return try {
            remember(host, Dns.SYSTEM.lookup(host))
        } catch (e: Exception) {
            Log.e(TAG, "FAILED $host: ${e.message}")
            emptyList()
        }
    }

    fun resolveFresh(hostname: String): List<InetAddress> {
        cache.remove(hostname.trim().lowercase().trimEnd('.'))
        return lookup(hostname)
    }

    /** All candidate IPs for racing HTTP (hints + resolve). */
    fun allCandidates(hostname: String): List<String> {
        val host = hostname.trim().lowercase()
        val out = LinkedHashSet<String>()
        lookup(host).mapNotNullTo(out) { it.hostAddress }
        staticHints[host]?.let { out.addAll(it) }
        return out.toList()
    }

    private fun remember(host: String, list: List<InetAddress>): List<InetAddress> {
        if (list.isNotEmpty()) {
            cache[host] = System.currentTimeMillis() to list
            staticHints[host] = list.mapNotNull { it.hostAddress }
            Log.i(TAG, "ok $host → ${list.map { it.hostAddress }}")
        }
        return list
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
        threads.forEach { runCatching { it.interrupt() } }
        return winner.get().orEmpty()
    }

    /** application/dns-json via https://1.1.1.1 and https://8.8.8.8 */
    private fun dohResolve(name: String): List<InetAddress> {
        val urls = listOf(
            "https://1.1.1.1/dns-query?name=$name&type=A",
            "https://8.8.8.8/resolve?name=$name&type=A",
            "https://1.0.0.1/dns-query?name=$name&type=A",
        )
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                dohHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val addrs = parseDnsJsonA(body)
                    if (addrs.isNotEmpty()) {
                        Log.i(TAG, "DoH $url → ${addrs.map { it.hostAddress }}")
                        return addrs
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "DoH $url: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseDnsJsonA(json: String): List<InetAddress> {
        return try {
            val obj = JSONObject(json)
            val ans = obj.optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<InetAddress>()
            for (i in 0 until ans.length()) {
                val a = ans.optJSONObject(i) ?: continue
                if (a.optInt("type") != 1) continue
                val data = a.optString("data").orEmpty()
                if (data.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                    runCatching { out += InetAddress.getByName(data) }
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun udpQueryA(name: String, resolverIp: String, timeoutMs: Int): List<InetAddress> {
        val query = buildQuery(name, type = 1)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.coerceIn(300, 1500)
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(resolverIp, 53)))
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

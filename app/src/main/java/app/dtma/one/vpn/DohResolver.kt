package app.dtma.one.vpn

import android.net.VpnService
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * DNS-over-HTTPS on a [VpnService.protect]-ed path.
 *
 * Helps when the ISP **poisons plain DNS** (common for YouTube/Google)
 * but HTTPS to DoH endpoints still works. Does nothing if the IP itself is null-routed.
 */
object DohResolver {
    private const val TAG = "DtmaDoH"

    /** Cloudflare + Google DoH (wireformat). */
    private val endpoints = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val CACHE_TTL_MS = 60_000L

    private data class CacheEntry(val wire: ByteArray, val at: Long)

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var boundVpn: VpnService? = null

    fun bind(vpn: VpnService?) {
        if (vpn === boundVpn && client != null) return
        boundVpn = vpn
        client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .socketFactory(ProtectedSocketFactory(vpn))
            .build()
        Log.i(TAG, "DoH client bound protect=${vpn != null}")
    }

    /**
     * @param query full DNS wire query from the app
     * @return DNS wire response, or null
     */
    fun resolveWire(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val cacheKey = Base64.encodeToString(query, Base64.NO_WRAP)
        val hit = cache[cacheKey]
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            return rewriteTxId(hit.wire, query)
        }

        val http = client ?: run {
            bind(null)
            client!!
        }
        val b64 = Base64.encodeToString(query, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        for (base in endpoints) {
            try {
                val req = Request.Builder()
                    .url("$base?dns=$b64")
                    .header("Accept", "application/dns-message")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.bytes() ?: return@use
                    if (body.size < 12) return@use
                    cache[cacheKey] = CacheEntry(body, System.currentTimeMillis())
                    if (cache.size > 256) {
                        // crude trim
                        cache.keys.take(64).forEach { cache.remove(it) }
                    }
                    Log.d(TAG, "DoH ok via $base bytes=${body.size}")
                    return rewriteTxId(body, query)
                }
            } catch (e: Exception) {
                Log.d(TAG, "DoH fail $base: ${e.message}")
            }
        }
        return null
    }

    /** Resolve hostname for diagnostics (A records). */
    fun resolveHost(hostname: String): List<InetAddress> {
        val q = buildQuery(hostname, type = 1)
        val wire = resolveWire(q) ?: return emptyList()
        return parseA(wire)
    }

    private fun rewriteTxId(response: ByteArray, query: ByteArray): ByteArray {
        if (response.size < 2 || query.size < 2) return response
        val out = response.copyOf()
        out[0] = query[0]
        out[1] = query[1]
        return out
    }

    private fun buildQuery(name: String, type: Int): ByteArray {
        val labels = name.trim().lowercase().trimEnd('.').split('.').filter { it.isNotEmpty() }
        val nameBytes = ArrayList<Byte>()
        for (l in labels) {
            val b = l.toByteArray(Charsets.US_ASCII)
            nameBytes += b.size.toByte()
            nameBytes.addAll(b.toList())
        }
        nameBytes += 0
        val buf = ByteArray(12 + nameBytes.size + 4)
        // random-ish id
        val id = (System.nanoTime() and 0xFFFF).toInt()
        buf[0] = (id ushr 8).toByte()
        buf[1] = (id and 0xFF).toByte()
        buf[2] = 0x01 // RD
        buf[3] = 0x00
        buf[4] = 0x00
        buf[5] = 0x01 // QDCOUNT
        var i = 12
        for (b in nameBytes) buf[i++] = b
        buf[i++] = (type ushr 8).toByte()
        buf[i++] = (type and 0xFF).toByte()
        buf[i++] = 0x00
        buf[i] = 0x01 // IN
        return buf
    }

    private fun parseA(data: ByteArray): List<InetAddress> {
        if (data.size < 12) return emptyList()
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        var i = 12
        // skip question
        while (i < data.size) {
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
            if (i >= data.size) return@repeat
            if (i < data.size && (data[i].toInt() and 0xC0) == 0xC0) i += 2
            else {
                while (i < data.size) {
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
            if (i + 10 > data.size) return@repeat
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 8
            val rdlen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (i + rdlen > data.size) return@repeat
            if (type == 1 && rdlen == 4) {
                runCatching {
                    out += InetAddress.getByAddress(data.copyOfRange(i, i + 4))
                }
            }
            i += rdlen
        }
        return out
    }

    private class ProtectedSocketFactory(
        private val vpn: VpnService?,
    ) : SocketFactory() {
        override fun createSocket(): Socket = open()
        override fun createSocket(host: String?, port: Int): Socket =
            open().also { it.connect(InetSocketAddress(host, port), 6_000) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket(host, port)
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            open().also { it.connect(InetSocketAddress(host, port), 6_000) }
        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = createSocket(address, port)

        private fun open(): Socket {
            val s = Socket()
            vpn?.protect(s)
            return s
        }
    }
}

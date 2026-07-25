package app.dtma.one.bypass

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.wireguard.crypto.KeyPair
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * WARP registration resilient to operator blocks / dead anycast.
 *
 * - Bootstrap DNS (UDP + DoH + static hints)
 * - TCP/443 pre-probe of candidate IPs (skip blackholes)
 * - Parallel race across IPs × API paths × Android networks (Wi‑Fi + LTE)
 * - HTTP/1.1 only (some DPI breaks H2)
 * - First HTTP 200 with config wins (≤12s overall)
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private const val OVERALL_SEC = 12L

    private val regPaths = listOf(
        "/v0a2158/reg",
        "/v0a1922/reg",
        "/v0a2471/reg",
    )

    val PEER_PORTS = listOf(2408, 443, 500)

    data class Result(
        val confText: String,
        val addressV4: String,
        val endpoint: String,
        val clientId: String,
        val privateKey: String,
        val publicKey: String,
        val endpointCandidates: List<String> = listOf(endpoint),
        val peerPublicKey: String = "",
    )

    fun generate(context: Context? = null): Result {
        val keys = KeyPair()
        val privB64 = keys.privateKey.toBase64()
        val pubB64 = keys.publicKey.toBase64()
        val bodyJson = JSONObject()
            .put("key", pubB64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", Instant.now().toString())
            .put("model", "PC")
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()
        val body = bodyJson.toRequestBody(jsonMedia)

        val rawIps = WarpBootstrapDns.allCandidates("api.cloudflareclient.com")
        val liveIps = tcpPrefilter(rawIps, port = 443, timeoutMs = 500).ifEmpty { rawIps }
        Log.i(TAG, "reg race raw=$rawIps live=$liveIps")

        val networks = discoverNetworks(context)
        Log.i(TAG, "reg networks=${networks.map { it.label }}")

        data class Target(
            val label: String,
            val client: OkHttpClient,
            val url: String,
        )

        val targets = ArrayList<Target>()
        val pathList = regPaths
        val ipList = liveIps.take(6)

        for (net in networks) {
            for (path in pathList) {
                // Hostname + bootstrap DNS on this network
                targets += Target(
                    label = "${net.label}/dns$path",
                    client = clientFor(net.factory, WarpBootstrapDns, connectSec = 4, callSec = 6),
                    url = "https://api.cloudflareclient.com$path",
                )
                for (ip in ipList) {
                    targets += Target(
                        label = "${net.label}/$ip$path",
                        client = clientFor(net.factory, fixedDns(ip), connectSec = 3, callSec = 5),
                        url = "https://api.cloudflareclient.com$path",
                    )
                }
            }
        }

        val winner = AtomicReference<Pair<String, String>?>(null)
        val errors = AtomicReference<String>("timeout")
        val attempts = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(12))

        try {
            for (t in targets) {
                pool.execute {
                    if (winner.get() != null) return@execute
                    attempts.incrementAndGet()
                    try {
                        val req = Request.Builder()
                            .url(t.url)
                            .header("User-Agent", "okhttp/3.12.1")
                            .header("CF-Client-Version", "a-6.30-2158")
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .post(body)
                            .build()
                        t.client.newCall(req).execute().use { resp ->
                            val text = resp.body?.string().orEmpty()
                            if (resp.isSuccessful && text.contains("\"config\"")) {
                                if (winner.compareAndSet(null, text to t.label)) {
                                    Log.i(TAG, "reg WIN via ${t.label}")
                                    latch.countDown()
                                }
                            } else {
                                Log.w(TAG, "reg ${t.label} HTTP ${resp.code}")
                                errors.set("HTTP ${resp.code}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "reg ${t.label}: ${e.message}")
                        errors.set(e.message ?: "error")
                    }
                }
            }

            val ok = latch.await(OVERALL_SEC, TimeUnit.SECONDS)
            pool.shutdownNow()

            val win = winner.get()
            if (win != null) {
                return parseResponse(win.first, privB64, pubB64)
            }
            throw IllegalStateException(
                if (ok) {
                    "WARP API: ${errors.get()}"
                } else {
                    "Таймаут Cloudflare API (${OVERALL_SEC}с, ${attempts.get()}/${targets.size} " +
                        "попыток, nets=${networks.size}, liveIP=${liveIps.size}). " +
                        "Часто API reg режется провайдером — LTE / другой Wi‑Fi / " +
                        "вставьте conf из буфера. err=${errors.get()}"
                },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /** Parse a WireGuard conf (from clipboard / file) into Result — no Cloudflare API. */
    fun fromConfText(confText: String): Result {
        val text = confText.trim()
        require(text.contains("[Interface]", ignoreCase = true)) { "Нет [Interface]" }
        require(text.contains("[Peer]", ignoreCase = true)) { "Нет [Peer]" }

        fun field(name: String): String {
            val re = Regex("""(?im)^\s*$name\s*=\s*(.+?)\s*$""")
            return re.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        }

        val privateKey = field("PrivateKey")
        require(privateKey.length >= 40) { "PrivateKey не найден" }
        val addressRaw = field("Address").substringBefore(',').trim()
        require(addressRaw.isNotBlank()) { "Address не найден" }
        val address = if (addressRaw.contains("/")) addressRaw else "$addressRaw/32"
        val peerPublicKey = field("PublicKey")
        require(peerPublicKey.length >= 40) { "Peer PublicKey не найден" }
        val endpoint = field("Endpoint")
        require(endpoint.contains(':')) { "Endpoint не найден (host:port)" }

        // Client public key not required for GoBackend tunnel
        val publicKey = ""

        val conf = confFor(privateKey, address, peerPublicKey, endpoint)
        val candidates = buildList {
            add(endpoint)
            val (ip, _) = WarpEndpoint.splitIpv4AndPort(endpoint)
            if (ip.contains('.')) {
                for (p in PEER_PORTS) add("$ip:$p")
            }
        }.distinct().take(3)

        Log.i(TAG, "fromConf endpoint=$endpoint")
        return Result(
            confText = conf,
            addressV4 = address,
            endpoint = endpoint,
            clientId = "",
            privateKey = privateKey,
            publicKey = publicKey,
            endpointCandidates = candidates,
            peerPublicKey = peerPublicKey,
        )
    }

    private data class NetSlot(val label: String, val factory: SocketFactory?)

    private fun discoverNetworks(context: Context?): List<NetSlot> {
        val out = ArrayList<NetSlot>()
        // Always include default route first
        out += NetSlot("default", null)
        if (context == null) return out

        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nets = cm.allNetworks.mapNotNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val label = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                    else -> "net"
                }
                NetSlot(label, NetworkSocketFactory(n))
            }
            // Prefer cellular first (often less censored for CF API than Wi‑Fi)
            val ordered = nets.sortedBy { if (it.label == "cell") 0 else 1 }
            // Avoid duplicate "default" only — keep real nets
            (listOf(NetSlot("default", null)) + ordered).distinctBy { it.label to (it.factory != null) }
        } catch (e: Exception) {
            Log.w(TAG, "discoverNetworks: ${e.message}")
            out
        }
    }

    /** Parallel TCP connect — keep IPs that accept connection. */
    private fun tcpPrefilter(ips: List<String>, port: Int, timeoutMs: Int): List<String> {
        if (ips.isEmpty()) return emptyList()
        val live = java.util.Collections.synchronizedList(ArrayList<String>())
        val latch = CountDownLatch(ips.size)
        for (ip in ips) {
            Thread({
                try {
                    Socket().use { s ->
                        s.soTimeout = timeoutMs
                        s.connect(InetSocketAddress(ip, port), timeoutMs)
                        live += ip
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }, "warp-tcp-$ip").apply { isDaemon = true; start() }
        }
        latch.await((timeoutMs + 200).toLong(), TimeUnit.MILLISECONDS)
        Log.i(TAG, "tcp/443 live=$live / $ips")
        return live.toList()
    }

    private class NetworkSocketFactory(private val network: Network) : SocketFactory() {
        private fun bound(): Socket {
            val s = Socket()
            try {
                network.bindSocket(s)
            } catch (e: Exception) {
                Log.d(TAG, "bindSocket: ${e.message}")
            }
            return s
        }

        override fun createSocket(): Socket = bound()
        override fun createSocket(host: String?, port: Int): Socket =
            bound().also { it.connect(InetSocketAddress(host, port), 5_000) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket(host, port)
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            bound().also { it.connect(InetSocketAddress(host, port), 5_000) }
        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = createSocket(address, port)
    }

    private fun fixedDns(ip: String): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName(ip))
    }

    private fun clientFor(
        socketFactory: SocketFactory?,
        dns: Dns,
        connectSec: Long,
        callSec: Long,
    ): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dns(dns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(callSec, TimeUnit.SECONDS)
            .writeTimeout(callSec, TimeUnit.SECONDS)
            .callTimeout(callSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        if (socketFactory != null) {
            b.socketFactory(socketFactory)
        }
        return b.build()
    }

    private fun parseResponse(text: String, privateKeyB64: String, publicKeyB64: String): Result {
        val root = JSONObject(text)
        val config = root.getJSONObject("config")
        val clientId = config.optString("client_id").orEmpty()
        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")
        var v4 = addresses.getString("v4").trim()
        if (!v4.contains("/")) v4 = "$v4/32"

        val peers = config.getJSONArray("peers")
        if (peers.length() < 1) error("No WARP peers")
        val peer = peers.getJSONObject(0)
        val peerPub = peer.getString("public_key")
        val endpointObj = peer.getJSONObject("endpoint")

        val primary = resolveEndpoint(endpointObj)
        val candidates = buildEndpointCandidates(endpointObj, primary)
        val conf = confFor(privateKeyB64, v4, peerPub, primary)

        val epPort = primary.substringAfterLast(':', "").toIntOrNull()
        if (epPort == null || epPort !in 1..65535) {
            error("Bad endpoint '$primary'")
        }

        Log.i(TAG, "conf endpoint=$primary candidates=${candidates.size}")
        return Result(
            confText = conf,
            addressV4 = v4,
            endpoint = primary,
            clientId = clientId,
            privateKey = privateKeyB64,
            publicKey = publicKeyB64,
            endpointCandidates = candidates,
            peerPublicKey = peerPub,
        )
    }

    fun confFor(
        privateKeyB64: String,
        addressCidr: String,
        peerPublicKey: String,
        endpoint: String,
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKeyB64")
        appendLine("Address = $addressCidr")
        appendLine("DNS = 1.1.1.1")
        appendLine("MTU = 1280")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKey")
        appendLine("AllowedIPs = 0.0.0.0/0")
        appendLine("Endpoint = $endpoint")
        appendLine("PersistentKeepalive = 25")
    }

    fun withEndpoint(base: Result, endpoint: String): Result {
        val conf = confFor(base.privateKey, base.addressV4, base.peerPublicKey, endpoint)
        return base.copy(confText = conf, endpoint = endpoint)
    }

    private fun buildEndpointCandidates(endpointObj: JSONObject, primary: String): List<String> {
        val out = LinkedHashSet<String>()
        out += primary
        val v4Field = endpointObj.optString("v4").orEmpty().trim().substringBefore('/')
        val (ip, _) = WarpEndpoint.splitIpv4AndPort(
            if (v4Field.contains(':')) v4Field else "$v4Field:2408",
        )
        if (ip.isNotBlank() && ip.contains('.')) {
            for (p in PEER_PORTS) out += "$ip:$p"
        }
        return out.take(3)
    }

    private fun resolveEndpoint(endpointObj: JSONObject): String {
        val hostField = endpointObj.optString("host").orEmpty().trim()
        val v4Field = endpointObj.optString("v4").orEmpty().trim().substringBefore('/')
        return WarpEndpoint.resolve(hostField, v4Field)
    }

    fun clientIdToReserved(clientId: String): ByteArray? {
        if (clientId.isBlank()) return null
        return try {
            val raw = Base64.decode(clientId, Base64.DEFAULT)
            if (raw.size >= 3) raw.copyOf(3) else null
        } catch (_: Exception) {
            null
        }
    }
}

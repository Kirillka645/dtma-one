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
 * WARP registration for blocked/throttled networks (LTE included).
 *
 * 1) Live register: multi-IP × multi-HTTPS-port × multi-path × multi-network race
 * 2) Fallback: bundled bootstrap confs in assets (pre-registered; no CF API)
 * 3) Endpoint expansion: engage anycast IPs × many WARP UDP ports
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private const val OVERALL_SEC = 14L
    private const val API_HOST = "api.cloudflareclient.com"

    /** Cloudflare HTTPS alternate ports (often less filtered than 443 alone). */
    private val httpsPorts = listOf(443, 8443, 2053, 2083, 2087, 2096)

    private val regPaths = listOf(
        "/v0a2158/reg",
        "/v0a1922/reg",
        "/v0a2471/reg",
    )

    /** Official WARP / WireGuard peer ports (incl. fallbacks). */
    val PEER_PORTS = listOf(2408, 443, 500, 1701, 4500, 4443, 8443, 8095)

    data class Result(
        val confText: String,
        val addressV4: String,
        val endpoint: String,
        val clientId: String,
        val privateKey: String,
        val publicKey: String,
        val endpointCandidates: List<String> = listOf(endpoint),
        val peerPublicKey: String = "",
        val source: String = "api",
    )

    /**
     * Prefer live API; on total failure use assets bootstrap confs so LTE
     * still has a chance when only the registration HTTPS is blocked.
     */
    fun generateOrBootstrap(context: Context): Result {
        return try {
            generate(context)
        } catch (apiErr: Exception) {
            Log.w(TAG, "API reg failed: ${apiErr.message}")
            val boot = loadBootstrap(context)
                ?: throw IllegalStateException(
                    "Таймаут CF API + нет bootstrap. " +
                        "${apiErr.message?.take(120)}. " +
                        "Вставьте conf из буфера.",
                )
            Log.i(TAG, "using bootstrap source=${boot.source} ep=${boot.endpoint}")
            boot
        }
    }

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

        val rawIps = WarpBootstrapDns.allCandidates(API_HOST)
        // Probe 443 and 8443; keep IPs that answer on either
        val live443 = tcpPrefilter(rawIps, port = 443, timeoutMs = 450)
        val live8443 = tcpPrefilter(rawIps, port = 8443, timeoutMs = 450)
        val liveIps = (live443 + live8443 + rawIps).distinct().take(8)
        Log.i(TAG, "reg IPs raw=$rawIps live443=$live443 live8443=$live8443 use=$liveIps")

        val networks = discoverNetworks(context)
        Log.i(TAG, "reg nets=${networks.map { it.label }}")

        data class Target(val label: String, val client: OkHttpClient, val url: String)

        val targets = ArrayList<Target>()
        // Prefer fewer high-value combos first: cell + 8443/443 + top paths
        val portsPrefer = listOf(8443, 443, 2053, 2083)
        for (net in networks) {
            for (port in portsPrefer) {
                for (path in regPaths.take(2)) {
                    targets += Target(
                        label = "${net.label}/dns:$port$path",
                        client = clientFor(net.factory, WarpBootstrapDns, connectSec = 4, callSec = 6),
                        url = "https://$API_HOST:$port$path",
                    )
                    for (ip in liveIps.take(4)) {
                        targets += Target(
                            label = "${net.label}/$ip:$port$path",
                            client = clientFor(net.factory, fixedDns(ip), connectSec = 3, callSec = 5),
                            url = "https://$API_HOST:$port$path",
                        )
                    }
                }
            }
        }

        val winner = AtomicReference<Pair<String, String>?>(null)
        val errors = AtomicReference<String>("timeout")
        val attempts = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(14))

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
                return parseResponse(win.first, privB64, pubB64).copy(source = "api:${win.second}")
            }
            throw IllegalStateException(
                if (ok) {
                    "WARP API: ${errors.get()}"
                } else {
                    "Таймаут Cloudflare API (${OVERALL_SEC}с, ${attempts.get()}/${targets.size}, " +
                        "ports=$portsPrefer, nets=${networks.size}). err=${errors.get()}"
                },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /** Bundled pre-registered confs under assets/warp_bootstrap. */
    fun loadBootstrap(context: Context): Result? {
        val am = context.assets
        val names = try {
            am.list("warp_bootstrap")?.filter { it.endsWith(".conf") }?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        for (name in names) {
            try {
                val text = am.open("warp_bootstrap/$name").bufferedReader().use { it.readText() }
                val r = fromConfText(text).let { expandEndpoints(it).copy(source = "bootstrap:$name") }
                Log.i(TAG, "bootstrap loaded $name candidates=${r.endpointCandidates.size}")
                return r
            } catch (e: Exception) {
                Log.w(TAG, "bootstrap $name: ${e.message}")
            }
        }
        return null
    }

    fun loadAllBootstrap(context: Context): List<Result> {
        val am = context.assets
        val names = try {
            am.list("warp_bootstrap")?.filter { it.endsWith(".conf") }?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return names.mapNotNull { name ->
            runCatching {
                val text = am.open("warp_bootstrap/$name").bufferedReader().use { it.readText() }
                expandEndpoints(fromConfText(text)).copy(source = "bootstrap:$name")
            }.getOrNull()
        }
    }

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

        val conf = confFor(privateKey, address, peerPublicKey, endpoint)
        return expandEndpoints(
            Result(
                confText = conf,
                addressV4 = address,
                endpoint = endpoint,
                clientId = "",
                privateKey = privateKey,
                publicKey = "",
                endpointCandidates = listOf(endpoint),
                peerPublicKey = peerPublicKey,
                source = "conf",
            ),
        )
    }

    /** Add engage anycast IPs × WARP ports for handshake race. */
    fun expandEndpoints(base: Result): Result {
        val out = LinkedHashSet<String>()
        out += base.endpoint
        val (hostOrIp, portHint) = WarpEndpoint.splitIpv4AndPort(base.endpoint)
        val basePort = if (portHint != null && portHint in 1..65535) portHint else 2408

        val ips = LinkedHashSet<String>()
        if (hostOrIp.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
            ips += hostOrIp
        } else {
            // hostname — resolve via bootstrap
            WarpBootstrapDns.allCandidates(
                hostOrIp.substringBefore(':').ifBlank { "engage.cloudflareclient.com" },
            ).forEach { ips += it }
            WarpBootstrapDns.allCandidates("engage.cloudflareclient.com").forEach { ips += it }
        }
        // Always include known engage hints even if DNS empty
        listOf(
            "162.159.192.1", "162.159.193.1", "162.159.195.1",
            "162.159.192.2", "162.159.193.5",
        ).forEach { ips += it }

        // Priority ports first
        val ports = listOf(basePort) + PEER_PORTS
        for (ip in ips) {
            for (p in ports.distinct()) {
                out += "$ip:$p"
            }
        }
        for (p in PEER_PORTS) {
            out += "engage.cloudflareclient.com:$p"
        }

        val candidates = out.toList().take(10)
        val primary = candidates.first()
        val conf = confFor(base.privateKey, base.addressV4, base.peerPublicKey, primary)
        return base.copy(
            confText = conf,
            endpoint = primary,
            endpointCandidates = candidates,
        )
    }

    private data class NetSlot(val label: String, val factory: SocketFactory?)

    private fun discoverNetworks(context: Context?): List<NetSlot> {
        val fallback = listOf(NetSlot("default", null))
        if (context == null) return fallback
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
            }.sortedBy { if (it.label == "cell") 0 else 1 }
            // cell first, then default, then others
            (nets + fallback).distinctBy { it.label }
        } catch (e: Exception) {
            Log.w(TAG, "discoverNetworks: ${e.message}")
            fallback
        }
    }

    private fun tcpPrefilter(ips: List<String>, port: Int, timeoutMs: Int): List<String> {
        if (ips.isEmpty()) return emptyList()
        val live = java.util.Collections.synchronizedList(ArrayList<String>())
        val latch = CountDownLatch(ips.size)
        for (ip in ips) {
            Thread({
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, port), timeoutMs)
                        live += ip
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }, "warp-tcp-$ip-$port").apply { isDaemon = true; start() }
        }
        latch.await((timeoutMs + 250).toLong(), TimeUnit.MILLISECONDS)
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
        if (socketFactory != null) b.socketFactory(socketFactory)
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
        val base = Result(
            confText = confFor(privateKeyB64, v4, peerPub, primary),
            addressV4 = v4,
            endpoint = primary,
            clientId = clientId,
            privateKey = privateKeyB64,
            publicKey = publicKeyB64,
            endpointCandidates = listOf(primary),
            peerPublicKey = peerPub,
            source = "api",
        )
        return expandEndpoints(base)
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

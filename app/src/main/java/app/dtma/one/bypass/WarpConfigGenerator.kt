package app.dtma.one.bypass

import android.util.Base64
import android.util.Log
import com.wireguard.crypto.KeyPair
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WARP registration with parallel multi-IP race (fixes "timeout Cloudflare API").
 *
 * Instead of one slow hang on a dead anycast IP, we POST to several resolved IPs
 * at once (short timeout); first HTTP 200 wins.
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val regPaths = listOf(
        "/v0a2158/reg",
        "/v0a1922/reg",
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

    fun generate(): Result {
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

        val ips = WarpBootstrapDns.allCandidates("api.cloudflareclient.com")
        Log.i(TAG, "reg race IPs=$ips")

        // Build targets: hostname (Dns) + pin each IP via single-IP Dns
        data class Target(val label: String, val client: OkHttpClient, val url: String)

        val targets = ArrayList<Target>()
        for (path in regPaths) {
            // Normal hostname — uses WarpBootstrapDns
            targets += Target(
                label = "host$path",
                client = clientWithDns(WarpBootstrapDns, connectSec = 4, callSec = 6),
                url = "https://api.cloudflareclient.com$path",
            )
            // Race each IP: URL still uses hostname for SNI/cert, Dns returns only that IP
            for (ip in ips.take(6)) {
                targets += Target(
                    label = "$ip$path",
                    client = clientWithDns(fixedDns(ip), connectSec = 3, callSec = 5),
                    url = "https://api.cloudflareclient.com$path",
                )
            }
        }

        val winner = AtomicReference<Pair<String, String>?>(null) // body, label
        val errors = AtomicReference<String>("timeout")
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(8))

        try {
            for (t in targets) {
                pool.execute {
                    if (winner.get() != null) return@execute
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
            // Overall budget — don't hang the UI for 30s+
            val ok = latch.await(8, TimeUnit.SECONDS)
            pool.shutdownNow()

            val win = winner.get()
            if (win != null) {
                return parseResponse(win.first, privB64, pubB64)
            }
            throw IllegalStateException(
                if (ok) {
                    "WARP API: ${errors.get()}"
                } else {
                    "Таймаут Cloudflare API (8с, ${targets.size} попыток). " +
                        "Сеть режет CF или медленная. LTE / другой Wi‑Fi. " +
                        "IPs=$ips err=${errors.get()}"
                },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fixedDns(ip: String): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName(ip))
    }

    private fun clientWithDns(dns: Dns, connectSec: Long, callSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(callSec, TimeUnit.SECONDS)
            .writeTimeout(callSec, TimeUnit.SECONDS)
            .callTimeout(callSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

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

package app.dtma.one.bypass

import android.util.Base64
import android.util.Log
import com.wireguard.crypto.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Free Cloudflare WARP account → WireGuard conf.
 *
 * DNS for api.cloudflareclient.com uses [WarpBootstrapDns] (UDP to 1.1.1.1/8.8.8.8/… by IP)
 * so operator DNS poison/blackhole does not block registration.
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** API path variants + optional IP-literal hosts after resolve. */
    private val regPathVersions = listOf(
        "v0a2158",
        "v0a1922",
        "v0a2477",
        "v0a2535",
        "v0a2592",
    )

    /** WireGuard peer ports to try when building conf candidates. */
    val PEER_PORTS = listOf(2408, 500, 4500, 1701, 443, 4443, 8443)

    data class Result(
        val confText: String,
        val addressV4: String,
        val endpoint: String,
        val clientId: String,
        val privateKey: String,
        val publicKey: String,
        /** Alternate endpoints (same peer key, other ports / IPs) for handshake retry. */
        val endpointCandidates: List<String> = listOf(endpoint),
        val peerPublicKey: String = "",
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(WarpBootstrapDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun generate(): Result {
        val keys = KeyPair()
        val privB64 = keys.privateKey.toBase64()
        val pubB64 = keys.publicKey.toBase64()
        val tos = Instant.now().toString()
        val bodyJson = JSONObject()
            .put("key", pubB64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("model", "PC")
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        // Warm DNS before HTTP (logs clearer)
        val apiIps = try {
            WarpBootstrapDns.resolveFresh("api.cloudflareclient.com")
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            Log.w(TAG, "pre-resolve api: ${e.message}")
            emptyList()
        }
        Log.i(TAG, "api.cloudflareclient.com → $apiIps")

        val urls = buildRegUrls(apiIps)
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "okhttp/3.12.1")
                    .header("CF-Client-Version", "a-6.30-2158")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Host", "api.cloudflareclient.com")
                    .post(bodyJson.toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "reg $url HTTP ${resp.code}: ${text.take(200)}")
                        lastError = IllegalStateException("WARP reg HTTP ${resp.code}")
                        return@use
                    }
                    Log.i(TAG, "reg ok via $url")
                    return parseResponse(text, privB64, pubB64)
                }
            } catch (e: Exception) {
                Log.w(TAG, "reg $url: ${e.message}")
                lastError = e
            }
        }
        throw lastError
            ?: IllegalStateException(
                "WARP registration failed (DNS/API). api IPs tried=$apiIps",
            )
    }

    private fun buildRegUrls(apiIps: List<String>): List<String> {
        val out = ArrayList<String>()
        // Hostname URLs (OkHttp uses WarpBootstrapDns)
        for (ver in regPathVersions) {
            out += "https://api.cloudflareclient.com/$ver/reg"
        }
        // Direct IP URLs — still send Host header for TLS SNI... OkHttp SNI uses URL host.
        // Connecting by IP with hostname URL is enough via Dns; IP URLs need careful TLS.
        // Prefer Dns override only — but also try IP with hostname as okhttp "hack":
        // https://104.x.x.x/v0a2158/reg won't pass cert for IP. So DNS fix is the real fix.
        // Keep IP list for logging / future pin.
        Log.d(TAG, "reg URL count=${out.size} bootstrapIps=$apiIps")
        return out
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
            error("Bad WARP endpoint port in '$primary'")
        }

        Log.i(TAG, "WARP conf addr=$v4 endpoint=$primary candidates=$candidates")
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
        appendLine("# DTMA One in-app Cloudflare WARP (IPv4)")
        appendLine()
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

    /** Swap only Endpoint line for port/IP retry without re-register. */
    fun withEndpoint(base: Result, endpoint: String): Result {
        val conf = confFor(base.privateKey, base.addressV4, base.peerPublicKey, endpoint)
        return base.copy(confText = conf, endpoint = endpoint)
    }

    private fun buildEndpointCandidates(endpointObj: JSONObject, primary: String): List<String> {
        val set = LinkedHashSet<String>()
        set += primary

        val hostField = endpointObj.optString("host").orEmpty()
        val v4Field = endpointObj.optString("v4").orEmpty().substringBefore('/')
        val (ipFromV4, _) = WarpEndpoint.splitIpv4AndPort(
            if (v4Field.contains(':') && v4Field.count { it == ':' } == 1) {
                v4Field.substringBefore(':') + ":2408"
            } else {
                v4Field.ifBlank { "0.0.0.0" }
            },
        )
        val ip = when {
            ipFromV4.isNotBlank() && ipFromV4 != "0.0.0.0" && !ipFromV4.contains(':') -> ipFromV4
            primary.count { it == ':' } == 1 && primary.substringBefore(':').contains('.') ->
                primary.substringBefore(':')
            else -> null
        }

        // Resolve engage host for extra anycast IPs
        val engageIps = try {
            WarpBootstrapDns.lookup("engage.cloudflareclient.com").mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }

        val ips = LinkedHashSet<String>()
        if (ip != null) ips += ip
        ips.addAll(engageIps)

        for (addr in ips) {
            for (port in PEER_PORTS) {
                set += "$addr:$port"
            }
        }
        // hostname + ports
        if (hostField.isNotBlank()) {
            val h = hostField.substringBefore(':')
            for (port in PEER_PORTS) {
                set += "$h:$port"
            }
        }
        return set.toList()
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

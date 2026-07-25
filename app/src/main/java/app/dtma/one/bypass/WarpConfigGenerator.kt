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
 * Fast WARP registration: bootstrap DNS + 1–2 API paths, short timeouts.
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Prefer one working API version; one fallback only. */
    private val regUrls = listOf(
        "https://api.cloudflareclient.com/v0a2158/reg",
        "https://api.cloudflareclient.com/v0a1922/reg",
    )

    /** Few ports for quick handshake (not 7×N explosion). */
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

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(WarpBootstrapDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

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

        var lastError: Exception? = null
        for (url in regUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "okhttp/3.12.1")
                    .header("CF-Client-Version", "a-6.30-2158")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .post(bodyJson.toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${resp.code}")
                        Log.w(TAG, "reg $url → ${resp.code}")
                        return@use
                    }
                    Log.i(TAG, "reg ok $url")
                    return parseResponse(text, privB64, pubB64)
                }
            } catch (e: Exception) {
                Log.w(TAG, "reg $url: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException(
            "Не удалось зарегистрировать WARP (DNS/API Cloudflare). " +
                "Проверьте интернет без VPN или смените сеть.",
        )
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

    /** At most ~3–4 endpoints for a fast start. */
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
        // one extra anycast from hints if different
        runCatching {
            WarpBootstrapDns.lookup("engage.cloudflareclient.com")
                .firstOrNull()?.hostAddress
        }.getOrNull()?.let { eng ->
            if (eng != ip) out += "$eng:2408"
        }
        return out.take(4)
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

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
 * Keys via official [KeyPair] (correct Curve25519 clamping).
 * IPv4-only + numeric endpoint (hostname DNS often breaks under VPN).
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val regUrls = listOf(
        "https://api.cloudflareclient.com/v0a2158/reg",
        "https://api.cloudflareclient.com/v0a1922/reg",
        "https://api.cloudflareclient.com/v0a2477/reg",
        "https://api.cloudflareclient.com/v0a2535/reg",
    )

    data class Result(
        val confText: String,
        val addressV4: String,
        val endpoint: String,
        val clientId: String,
        val privateKey: String,
        val publicKey: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

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
                        Log.w(TAG, "reg $url HTTP ${resp.code}: ${text.take(240)}")
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
        throw lastError ?: IllegalStateException("WARP registration failed")
    }

    private fun parseResponse(text: String, privateKeyB64: String, publicKeyB64: String): Result {
        val root = JSONObject(text)
        val config = root.getJSONObject("config")
        val clientId = config.optString("client_id").orEmpty()
        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")
        var v4 = addresses.getString("v4").trim()
        // Address must be CIDR for WireGuard conf
        if (!v4.contains("/")) v4 = "$v4/32"

        val peers = config.getJSONArray("peers")
        if (peers.length() < 1) error("No WARP peers")
        val peer = peers.getJSONObject(0)
        val peerPub = peer.getString("public_key")
        val endpointObj = peer.getJSONObject("endpoint")

        // Prefer raw IPv4:port so we do not depend on DNS under the tunnel.
        val endpoint = resolveEndpoint(endpointObj)

        // IPv4-only full tunnel — dual-stack AllowedIPs/Address often blackholes traffic
        // on networks with broken IPv6.
        val conf = buildString {
            appendLine("# DTMA One in-app Cloudflare WARP (IPv4)")
            appendLine("# client_id=$clientId")
            appendLine()
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKeyB64")
            appendLine("Address = $v4")
            appendLine("DNS = 1.1.1.1")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPub")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("Endpoint = $endpoint")
            appendLine("PersistentKeepalive = 25")
        }

        // Append reserved= for wireguard-go uapi if we can inject later via userspace string.
        // client_id is 3-byte cookie Cloudflare expects on handshake packets.
        // Sanity: WireGuard rejects port 0 / missing port hard with ParseException.
        val epPort = endpoint.substringAfterLast(':', "").toIntOrNull()
        if (epPort == null || epPort !in 1..65535) {
            error("Bad WARP endpoint port in '$endpoint' (API host/v4 malformed)")
        }

        Log.i(TAG, "WARP conf addr=$v4 endpoint=$endpoint clientIdLen=${clientId.length}")
        return Result(
            confText = conf,
            addressV4 = v4,
            endpoint = endpoint,
            clientId = clientId,
            privateKey = privateKeyB64,
            publicKey = publicKeyB64,
        )
    }

    private fun resolveEndpoint(endpointObj: JSONObject): String {
        val hostField = endpointObj.optString("host").orEmpty().trim()
        val v4Field = endpointObj.optString("v4").orEmpty().trim().substringBefore('/')
        return WarpEndpoint.resolve(hostField, v4Field)
    }

    /** Decode Cloudflare client_id (base64) to 3 bytes for WireGuard reserved field. */
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

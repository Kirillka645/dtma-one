package app.dtma.one.bypass

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Creates a free Cloudflare WARP account (WireGuard credentials) via the public
 * client registration API — same idea as [wgcf](https://github.com/ViRb3/wgcf).
 *
 * Traffic then exits via **Cloudflare**, not your ISP. That is the only free path
 * that can realistically unblock both YouTube and Telegram when local DTMA cannot.
 *
 * DTMA does **not** operate this network; Cloudflare does.
 */
object WarpConfigGenerator {
    private const val TAG = "DtmaWarp"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Known API paths (Cloudflare rotates minor versions). */
    private val regUrls = listOf(
        "https://api.cloudflareclient.com/v0a2158/reg",
        "https://api.cloudflareclient.com/v0a1922/reg",
        "https://api.cloudflareclient.com/v0a2477/reg",
    )

    data class Result(
        val confText: String,
        val addressV4: String,
        val endpoint: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun generate(): Result {
        val (privB64, pubB64) = generateKeyPair()
        val tos = Instant.now().toString()
        val bodyJson = JSONObject()
            .put("key", pubB64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("model", "DTMA One")
            .put("serial_number", "")
            .put("locale", "en_US")
            .toString()

        var lastError: Exception? = null
        for (url in regUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "okhttp/3.12.1")
                    .header("CF-Client-Version", "a-6.30-2158")
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "reg $url HTTP ${resp.code}: ${text.take(200)}")
                        lastError = IllegalStateException("WARP reg HTTP ${resp.code}")
                        return@use
                    }
                    return parseResponse(text, privB64)
                }
            } catch (e: Exception) {
                Log.w(TAG, "reg $url: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("WARP registration failed")
    }

    private fun parseResponse(text: String, privateKeyB64: String): Result {
        val root = JSONObject(text)
        val config = root.getJSONObject("config")
        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")
        val v4 = addresses.getString("v4")
        val v6 = addresses.optString("v6").ifBlank { null }

        val peers = config.getJSONArray("peers")
        if (peers.length() < 1) error("No WARP peers in response")
        val peer = peers.getJSONObject(0)
        val peerPub = peer.getString("public_key")
        val endpointObj = peer.getJSONObject("endpoint")
        val host = endpointObj.optString("host").ifBlank {
            val v4e = endpointObj.optString("v4")
            if (v4e.isNotBlank()) "$v4e:2408" else "engage.cloudflareclient.com:2408"
        }

        val addressLine = buildString {
            append(if (v4.contains("/")) v4 else "$v4/32")
            if (v6 != null) {
                append(", ")
                append(if (v6.contains("/")) v6 else "$v6/128")
            }
        }

        val conf = buildString {
            appendLine("# DTMA One — Cloudflare WARP (free)")
            appendLine("# Import into official WireGuard app, then enable tunnel.")
            appendLine("# Disable DTMA VPN while WARP/WireGuard is on (avoid double tunnel).")
            appendLine()
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKeyB64")
            appendLine("Address = $addressLine")
            appendLine("DNS = 1.1.1.1, 1.0.0.1")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPub")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $host")
            appendLine("PersistentKeepalive = 25")
        }

        Log.i(TAG, "WARP conf ready addr=$v4 endpoint=$host")
        return Result(confText = conf, addressV4 = v4, endpoint = host)
    }

    private fun generateKeyPair(): Pair<String, String> {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey()
        val privB64 = Base64.encodeToString(priv.encoded, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
        return privB64 to pubB64
    }
}

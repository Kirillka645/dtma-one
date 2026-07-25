package app.dtma.one.bypass

/**
 * Cloudflare WARP endpoint shapes:
 * - host: "engage.cloudflareclient.com:2408"
 * - v4: "162.159.192.1" | "162.159.192.1:0" | "162.159.192.1:2408"
 *
 * Port **0** is invalid for WireGuard → ParseException: Missing/invalid port number.
 */
object WarpEndpoint {
    private const val DEFAULT = "engage.cloudflareclient.com:2408"
    private const val DEFAULT_PORT = 2408

    fun resolve(hostField: String, v4Field: String): String {
        val host = hostField.trim()
        val v4 = v4Field.trim().substringBefore('/')

        // 1) Prefer host with a valid port (most common API shape).
        if (host.isNotBlank()) {
            // hostname:port (exactly one colon for hostnames)
            if (host.count { it == ':' } == 1) {
                val p = host.substringAfterLast(':').toIntOrNull()
                if (validPort(p)) return host
            }
            if (!host.contains(':')) {
                return "$host:$DEFAULT_PORT"
            }
        }

        // 2) IPv4 — strip :0, keep real port.
        if (v4.isNotBlank()) {
            val (ip, portInV4) = splitIpv4AndPort(v4)
            val portFromHost = if (host.count { it == ':' } == 1) {
                host.substringAfterLast(':').toIntOrNull()
            } else {
                null
            }
            val port = when {
                validPort(portInV4) -> portInV4!!
                validPort(portFromHost) -> portFromHost!!
                else -> DEFAULT_PORT
            }
            if (ip.isNotBlank()) return "$ip:$port"
        }

        return DEFAULT
    }

    private fun validPort(p: Int?) = p != null && p in 1..65535

    /** "1.2.3.4", "1.2.3.4:2408", "1.2.3.4:0" → (ip, port?) */
    internal fun splitIpv4AndPort(raw: String): Pair<String, Int?> {
        val s = raw.trim()
        val idx = s.lastIndexOf(':')
        if (idx <= 0) return s to null
        val maybePort = s.substring(idx + 1).toIntOrNull() ?: return s to null
        val ip = s.substring(0, idx)
        if (ip.contains(':')) return s to null // IPv6
        return ip to maybePort
    }
}

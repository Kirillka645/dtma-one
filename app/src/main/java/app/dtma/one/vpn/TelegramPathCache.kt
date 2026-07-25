package app.dtma.one.vpn

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Remembers which Telegram IP:port pairs actually accepted TCP.
 * Used by smart path to prefer working ports (80/5222 when 443 is blackholed).
 *
 * Never remaps to a *different* DC IP — that breaks MTProto auth.
 */
object TelegramPathCache {
    private const val TAG = "DtmaTgCache"

    data class WorkingEndpoint(
        val host: String,
        val port: Int,
        val via: String,
        val rttMs: Long,
        val atMs: Long = System.currentTimeMillis(),
    )

    /** host → ports known good (most recent first). */
    private val goodPorts = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()

    /** "host:port" → last failure epoch ms */
    private val recentFail = ConcurrentHashMap<String, Long>()

    private val working = CopyOnWriteArrayList<WorkingEndpoint>()

    @Volatile
    var lastProbeOk: Int = 0
        private set

    @Volatile
    var lastProbeTotal: Int = 0
        private set

    fun clear() {
        goodPorts.clear()
        recentFail.clear()
        working.clear()
        lastProbeOk = 0
        lastProbeTotal = 0
    }

    fun rememberOk(host: String, port: Int, via: String = "direct", rttMs: Long = 0) {
        val h = normalizeHost(host) ?: return
        val list = goodPorts.getOrPut(h) { CopyOnWriteArrayList() }
        list.remove(port)
        list.add(0, port)
        while (list.size > 6) list.removeAt(list.lastIndex)
        recentFail.remove(key(h, port))
        working.removeAll { it.host == h && it.port == port }
        working.add(0, WorkingEndpoint(h, port, via, rttMs))
        while (working.size > 32) working.removeAt(working.lastIndex)
        Log.i(TAG, "ok $h:$port via=$via ${rttMs}ms")
    }

    fun rememberFail(host: String, port: Int) {
        val h = normalizeHost(host) ?: return
        recentFail[key(h, port)] = System.currentTimeMillis()
    }

    fun ingestProbe(results: List<TelegramDcProbe.Result>) {
        lastProbeTotal = results.size
        lastProbeOk = results.count { it.ok }
        for (r in results) {
            if (r.ok) {
                rememberOk(r.target.host, r.target.port, via = "probe", rttMs = r.ms)
            } else {
                rememberFail(r.target.host, r.target.port)
            }
        }
        Log.i(TAG, "probe ingested $lastProbeOk/$lastProbeTotal working=${snapshotShort()}")
    }

    /**
     * Port order for racing: known-good for this host, then requested, then Telegram defaults.
     * Recently failed (host,port) go last (still tried — blackhole may be temporary).
     */
    fun preferredPorts(host: String, requestedPort: Int): List<Int> {
        val h = normalizeHost(host) ?: return listOf(requestedPort)
        val known = goodPorts[h]?.toList().orEmpty()
        val defaults = listOf(443, 80, 5222, 5223)
        val ordered = LinkedHashSet<Int>()
        known.forEach { ordered.add(it) }
        ordered.add(requestedPort)
        defaults.forEach { ordered.add(it) }

        val now = System.currentTimeMillis()
        val primary = ArrayList<Int>()
        val deferred = ArrayList<Int>()
        for (p in ordered) {
            val failedAt = recentFail[key(h, p)]
            // Defer ports that failed in the last 2 minutes (unless only option)
            if (failedAt != null && now - failedAt < 120_000L && p != requestedPort && p !in known) {
                deferred.add(p)
            } else {
                primary.add(p)
            }
        }
        return primary + deferred
    }

    fun workingEndpoints(): List<WorkingEndpoint> = working.toList()

    fun snapshotShort(): String {
        if (working.isEmpty()) return "none"
        return working.take(8).joinToString { "${it.host.substringAfterLast('.')}:${it.port}" }
    }

    fun summaryForUi(): String {
        val ep = workingEndpoints().distinctBy { "${it.host}:${it.port}" }
        if (ep.isEmpty()) {
            return "Smart path: нет известных живых DC (запустите probe или включите VPN — прогрев)."
        }
        val lines = ep.take(12).joinToString("\n") {
            "✓ ${it.host}:${it.port} (${it.via}, ${it.rttMs}ms)"
        }
        return "Smart path cache ($lastProbeOk/$lastProbeTotal last probe):\n$lines\n\n" +
            "При CONNECT Telegram: гонка портов 443/80/5222 + multipath на ТОМ ЖЕ IP " +
            "(чужой DC не подменяем — сломает вход)."
    }

    private fun key(host: String, port: Int) = "$host:$port"

    private fun normalizeHost(host: String): String? {
        val h = host.trim().lowercase().removePrefix("/").substringBefore('%')
        return h.ifBlank { null }
    }
}

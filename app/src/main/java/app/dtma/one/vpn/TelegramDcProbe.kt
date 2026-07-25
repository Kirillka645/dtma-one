package app.dtma.one.vpn

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Probes known Telegram infrastructure endpoints on standard ports.
 * Same ISP egress as local VPN — if all fail, local-only mode cannot reach Telegram.
 */
object TelegramDcProbe {

    data class Target(val host: String, val port: Int, val label: String)

    data class Result(
        val target: Target,
        val ok: Boolean,
        val ms: Long,
        val error: String?,
    )

    private val hosts = listOf(
        "149.154.167.50" to "DC2",
        "149.154.167.51" to "DC2b",
        "149.154.175.50" to "DC4",
        "149.154.175.100" to "DC4b",
        "91.108.56.100" to "DC5",
        "91.108.56.116" to "DC5b",
        "149.154.167.91" to "DC2c",
        "91.108.4.136" to "DC1",
    )

    /** Officially used Telegram client ports. */
    private val ports = listOf(443, 80, 5222)

    val DEFAULT_TARGETS: List<Target> = buildList {
        for ((ip, name) in hosts) {
            for (p in ports) {
                add(Target(ip, p, "$name:$p"))
            }
        }
        // IPv6 samples
        add(Target("2001:67c:4e8:f004::9", 443, "DC2-v6:443"))
        add(Target("2001:b28:f23d:f001::a", 443, "DC4-v6:443"))
    }

    suspend fun probeAll(
        targets: List<Target> = DEFAULT_TARGETS,
        timeoutMs: Int = 3500,
    ): List<Result> = coroutineScope {
        targets.map { t ->
            async(Dispatchers.IO) { probeOne(t, timeoutMs) }
        }.awaitAll()
    }

    fun summarize(results: List<Result>): String {
        val ok = results.filter { it.ok }
        val sb = StringBuilder()
        sb.append("Telegram DC probe: ${ok.size}/${results.size} reachable\n\n")
        results.forEach { r ->
            sb.append(
                if (r.ok) "✓ ${r.target.label} ${r.target.host} ${r.ms}ms\n"
                else "✗ ${r.target.label} ${r.target.host} — ${r.error}\n",
            )
        }
        sb.append('\n')
        if (ok.isEmpty()) {
            sb.append(
                "ВЫВОД: ни один DC Telegram не отвечает с этой сети (timeout/unreachable).\n\n" +
                    "Локальный VPN без внешнего пути НЕ создаёт маршруты к заблокированным IP.\n\n" +
                    "Без SOCKS5 попробуйте:\n" +
                    "1) Multipath: Wi‑Fi + мобильные данные — DTMA шлёт Telegram через LTE, " +
                    "если домашний Wi‑Fi режет DC (Настройки → Telegram multipath).\n" +
                    "2) MTProto proxy в Telegram (Настройки DTMA → «Открыть в Telegram»).\n" +
                    "3) Другая сеть / SIM, где probe > 0.\n\n" +
                    "Если есть сервер:\n" +
                    "• Upstream SOCKS5 в DTMA, или MTProto на своём VPS.\n" +
                    "3) Другая сеть (мобильный интернет / Wi‑Fi), где probe > 0.\n\n" +
                    "Это не баг dataplane: example.com ходит, DC Telegram — нет.",
            )
        } else {
            sb.append("Доступны: ${ok.joinToString { it.target.label }}.\n")
            sb.append(
                "Smart path (0.2.6+): при CONNECT к DC гоняет порты/сети и предпочитает эти ✓. " +
                    "Если «ваш» DC среди ✗ — клиент всё равно может висеть; тогда MTProto/другая сеть.\n" +
                    "Включите VPN (smart path ON) → перезапустите Telegram.\n",
            )
        }
        return sb.toString()
    }

    private fun probeOne(target: Target, timeoutMs: Int): Result {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                Result(target, true, System.currentTimeMillis() - t0, null)
            }
        } catch (_: SocketTimeoutException) {
            Result(target, false, System.currentTimeMillis() - t0, "timeout")
        } catch (e: IOException) {
            val msg = e.message ?: "io"
            val short = when {
                msg.contains("ENETUNREACH", true) -> "ENETUNREACH"
                msg.contains("ECONNREFUSED", true) -> "refused"
                msg.contains("EHOSTUNREACH", true) -> "host unreachable"
                else -> msg.take(40)
            }
            Result(target, false, System.currentTimeMillis() - t0, short)
        } catch (e: Exception) {
            Result(target, false, System.currentTimeMillis() - t0, e.message ?: "error")
        }
    }
}

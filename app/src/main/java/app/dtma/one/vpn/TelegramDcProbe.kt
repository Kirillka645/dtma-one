package app.dtma.one.vpn

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Diagnoses whether common Telegram DC endpoints are reachable from this device.
 * Same ISP path as local VPN egress (no remote proxy) — if these fail, Telegram
 * cannot work through DTMA One either.
 */
object TelegramDcProbe {

    data class Target(val host: String, val port: Int = 443, val label: String)

    data class Result(
        val target: Target,
        val ok: Boolean,
        val ms: Long,
        val error: String?,
    )

    /** Public Telegram DC / edge endpoints commonly used by clients. */
    val DEFAULT_TARGETS: List<Target> = listOf(
        Target("149.154.167.50", 443, "DC2-IPv4"),
        Target("149.154.167.51", 443, "DC2b-IPv4"),
        Target("149.154.175.50", 443, "DC4-IPv4"),
        Target("149.154.175.100", 443, "DC4b-IPv4"),
        Target("91.108.56.100", 443, "DC5-IPv4"),
        Target("91.108.56.116", 443, "DC5b-IPv4"),
        Target("2001:67c:4e8:f004::9", 443, "DC2-IPv6"),
        Target("2001:b28:f23d:f001::a", 443, "DC4-IPv6"),
    )

    suspend fun probeAll(
        targets: List<Target> = DEFAULT_TARGETS,
        timeoutMs: Int = 4000,
    ): List<Result> = coroutineScope {
        targets.map { t ->
            async(Dispatchers.IO) { probeOne(t, timeoutMs) }
        }.awaitAll()
    }

    fun summarize(results: List<Result>): String {
        val ok = results.count { it.ok }
        val sb = StringBuilder()
        sb.append("Telegram DC probe: $ok/${results.size} reachable\n")
        results.forEach { r ->
            sb.append(
                if (r.ok) "✓ ${r.target.label} ${r.target.host}:${r.target.port} ${r.ms}ms\n"
                else "✗ ${r.target.label} ${r.target.host}:${r.target.port} — ${r.error}\n",
            )
        }
        if (ok == 0) {
            sb.append(
                "\nВсе DC недоступны с этого IP/провайдера. " +
                    "Локальный VPN DTMA One выходит в интернет ТЕМ ЖЕ каналом — " +
                    "он не разблокирует null-route Telegram. " +
                    "Нужен другой маршрут (внешний прокси/VPN), чего DTMA One намеренно не делает.\n",
            )
        } else if (ok < results.size) {
            sb.append(
                "\nЧасть DC доступна. Если TG всё равно висит — перезапустите TG, " +
                    "проверьте что VPN 0.2.1+ активен, или смените сеть.\n",
            )
        } else {
            sb.append("\nDC отвечают. Если TG висит — проблема в клиенте/сессии, не в маршруте.\n")
        }
        return sb.toString()
    }

    private fun probeOne(target: Target, timeoutMs: Int): Result {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                Result(target, true, System.currentTimeMillis() - t0, null)
            }
        } catch (_: SocketTimeoutException) {
            Result(target, false, System.currentTimeMillis() - t0, "timeout")
        } catch (e: IOException) {
            Result(target, false, System.currentTimeMillis() - t0, e.message ?: "io")
        } catch (e: Exception) {
            Result(target, false, System.currentTimeMillis() - t0, e.message ?: "error")
        }
    }
}

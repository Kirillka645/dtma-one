package app.dtma.one.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Known Telegram DC / infra address space (public knowledge).
 * Used for multipath egress and diagnostics — not for "inventing" routes.
 */
object TelegramRanges {

    fun isTelegramHost(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> isTelegramV4(addr.address)
        is Inet6Address -> isTelegramV6(addr)
        else -> false
    }

    fun isTelegramHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.endsWith(".telegram.org") || h == "telegram.org" ||
            h.endsWith(".t.me") || h == "t.me" ||
            h.endsWith(".telegra.ph")
        ) {
            return true
        }
        return runCatching {
            isTelegramHost(InetAddress.getByName(h))
        }.getOrDefault(false)
    }

    /** 149.154.160.0/20, 91.108.4.0/22, 91.108.8.0/21, 91.108.16.0/21, 91.108.56.0/22, … */
    private fun isTelegramV4(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = b[2].toInt() and 0xFF
        // 149.154.160.0 – 149.154.175.255
        if (a == 149 && c == 154 && d in 160..175) return true
        // 91.108.0.0/16 (covers published DCs; slightly wide but safe for "try multipath")
        if (a == 91 && c == 108) return true
        return false
    }

    private fun isTelegramV6(addr: Inet6Address): Boolean {
        val h = addr.hostAddress?.lowercase() ?: return false
        // Published samples: 2001:67c:4e8::/48, 2001:b28:f23d::/48, 2001:b28:f23f::/48
        return h.startsWith("2001:67c:4e8") ||
            h.startsWith("2001:b28:f23d") ||
            h.startsWith("2001:b28:f23f") ||
            h.startsWith("2001:b28:f23c")
    }
}

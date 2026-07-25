package app.dtma.one.core.network.dns

import android.net.VpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * DNS client that uses a protected socket so queries do not re-enter the TUN.
 * Speaks standard DNS over UDP to configured upstream resolvers only (no port scan).
 */
class ProtectedDnsClient(
    private val vpnService: VpnService?,
    private val upstreams: List<InetAddress> = listOf(
        InetAddress.getByName("1.1.1.1"),
        InetAddress.getByName("8.8.8.8"),
    ),
) {
    data class Answer(val addresses: List<InetAddress>)

    fun resolve(hostname: String, timeoutMs: Int = 1500): Answer {
        val host = hostname.trim().lowercase().substringBefore('/').substringBefore('?')
        if (host.isEmpty()) return Answer(emptyList())

        // If host is already an IP literal
        runCatching {
            val lit = InetAddress.getByName(host)
            if (lit.hostAddress == host || host.contains(':') || host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                return Answer(listOf(lit))
            }
        }

        val v4 = queryType(host, 1, timeoutMs)
        val v6 = queryType(host, 28, timeoutMs)
        return Answer(v4 + v6)
    }

    private fun queryType(host: String, type: Int, timeoutMs: Int): List<InetAddress> {
        val query = buildQuery(host, type)
        for (upstream in upstreams) {
            try {
                val socket = DatagramSocket()
                try {
                    vpnService?.protect(socket)
                    socket.soTimeout = timeoutMs
                    val packet = DatagramPacket(query, query.size, InetSocketAddress(upstream, 53))
                    socket.send(packet)
                    val buf = ByteArray(2048)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    return parseAnswers(buf, resp.length, type)
                } finally {
                    socket.close()
                }
            } catch (_: Exception) {
                // try next upstream
            }
        }
        return emptyList()
    }

    private fun buildQuery(name: String, type: Int): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        val nameBytes = ArrayList<Byte>()
        for (l in labels) {
            val b = l.toByteArray(Charsets.US_ASCII)
            nameBytes += b.size.toByte()
            nameBytes.addAll(b.toList())
        }
        nameBytes += 0
        val buf = ByteBuffer.allocate(12 + nameBytes.size + 4)
        buf.putShort(0x1234.toShort()) // id
        buf.putShort(0x0100.toShort()) // RD
        buf.putShort(1) // QD
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(nameBytes.toByteArray())
        buf.putShort(type.toShort())
        buf.putShort(1) // IN
        return buf.array()
    }

    private fun parseAnswers(data: ByteArray, length: Int, expectedType: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        var i = 12
        // skip question
        while (i < length) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                i += 5
                break
            }
            if (len and 0xC0 == 0xC0) {
                i += 6
                break
            }
            i += 1 + len
        }
        val out = mutableListOf<InetAddress>()
        repeat(anCount) {
            if (i >= length) return@repeat
            // name
            if (i < length && (data[i].toInt() and 0xC0) == 0xC0) {
                i += 2
            } else {
                while (i < length) {
                    val len = data[i].toInt() and 0xFF
                    if (len == 0) {
                        i++
                        break
                    }
                    if (len and 0xC0 == 0xC0) {
                        i += 2
                        break
                    }
                    i += 1 + len
                }
            }
            if (i + 10 > length) return@repeat
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val rdLength = ((data[i + 8].toInt() and 0xFF) shl 8) or (data[i + 9].toInt() and 0xFF)
            i += 10
            if (i + rdLength > length) return@repeat
            if (type == expectedType) {
                if (type == 1 && rdLength == 4) {
                    out += InetAddress.getByAddress(data.copyOfRange(i, i + 4))
                } else if (type == 28 && rdLength == 16) {
                    out += InetAddress.getByAddress(data.copyOfRange(i, i + 16))
                }
            }
            i += rdLength
        }
        return out
    }
}

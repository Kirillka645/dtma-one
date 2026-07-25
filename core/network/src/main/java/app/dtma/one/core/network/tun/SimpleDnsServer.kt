package app.dtma.one.core.network.tun

import app.dtma.one.core.model.CandidatePlanner
import app.dtma.one.core.model.CandidateSource
import app.dtma.one.core.model.EndpointCandidate
import app.dtma.one.core.model.IpFamily
import app.dtma.one.core.model.Transport
import app.dtma.one.core.network.dns.ProtectedDnsClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DNS responder for VPN-managed DNS.
 * Upstream resolution uses [ProtectedDnsClient] (protect) to avoid TUN loops.
 */
class SimpleDnsServer(
    private val sessionCache: DnsSessionCache,
    private val networkContextId: String,
    private val protectedDns: ProtectedDnsClient,
    private val rvecProvider: (String) -> List<EndpointCandidate> = { emptyList() },
    private val onResolved: (String, List<EndpointCandidate>) -> Unit = { _, _ -> },
) {
    fun handleQuery(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val flags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        val opcode = (flags shr 11) and 0xF
        if (opcode != 0) return buildServFail(query)

        val qCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qCount < 1) return buildServFail(query)

        val (name, qType, qClass, _) = parseQuestion(query) ?: return buildServFail(query)
        if (qClass != 1) return buildServFail(query)

        val wantV4 = qType == 1 || qType == 255
        val wantV6 = qType == 28 || qType == 255
        if (!wantV4 && !wantV6) {
            return buildResponse(query, name, qType, qClass, emptyList(), emptyList())
        }

        val now = System.currentTimeMillis()
        val resolved = protectedDns.resolve(name).addresses

        val dnsCandidates = resolved.mapNotNull { addr ->
            val ip = addr.hostAddress ?: return@mapNotNull null
            val family = if (addr is Inet6Address) IpFamily.IPV6 else IpFamily.IPV4
            EndpointCandidate(
                hostname = name,
                ipAddress = ip,
                ipFamily = family,
                port = 443,
                transport = Transport.TCP,
                source = CandidateSource.CURRENT_DNS,
                networkContextId = networkContextId,
                discoveredAt = now,
            )
        }
        val rvec = rvecProvider(name)
        val planned = CandidatePlanner.plan(dnsCandidates, rvec, now, networkContextId)
        onResolved(name, planned)

        val v4 = planned.filter { it.ipFamily == IpFamily.IPV4 }.map { it.ipAddress }
        val v6 = planned.filter { it.ipFamily == IpFamily.IPV6 }.map { it.ipAddress }
        val orderedV4 = if (wantV4) {
            v4.ifEmpty { resolved.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
        } else {
            emptyList()
        }
        val orderedV6 = if (wantV6) {
            v6.ifEmpty { resolved.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress } }
        } else {
            emptyList()
        }

        sessionCache.remember(name, orderedV4 + orderedV6)
        return buildResponse(query, name, qType, qClass, orderedV4, orderedV6)
    }

    private data class Question(val name: String, val type: Int, val qClass: Int, val end: Int)

    private fun parseQuestion(query: ByteArray): Question? {
        var i = 12
        val labels = mutableListOf<String>()
        while (i < query.size) {
            val len = query[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if (len and 0xC0 == 0xC0) return null
            if (i + 1 + len > query.size) return null
            labels += String(query, i + 1, len, Charsets.US_ASCII)
            i += 1 + len
        }
        if (i + 4 > query.size) return null
        val type = ((query[i].toInt() and 0xFF) shl 8) or (query[i + 1].toInt() and 0xFF)
        val qClass = ((query[i + 2].toInt() and 0xFF) shl 8) or (query[i + 3].toInt() and 0xFF)
        return Question(labels.joinToString("."), type, qClass, i + 4)
    }

    private fun buildServFail(query: ByteArray): ByteArray {
        val out = query.copyOf(query.size.coerceAtLeast(12))
        out[2] = 0x81.toByte()
        out[3] = 0x82.toByte()
        return out
    }

    private fun buildResponse(
        query: ByteArray,
        name: String,
        qType: Int,
        qClass: Int,
        v4: List<String>,
        v6: List<String>,
    ): ByteArray {
        val answers = mutableListOf<ByteArray>()
        if (qType == 1 || qType == 255) {
            for (ip in v4) {
                val addr = InetAddress.getByName(ip).address
                if (addr.size == 4) answers += encodeAnswer(name, 1, qClass, 60, addr)
            }
        }
        if (qType == 28 || qType == 255) {
            for (ip in v6) {
                val addr = InetAddress.getByName(ip).address
                if (addr.size == 16) answers += encodeAnswer(name, 28, qClass, 60, addr)
            }
        }

        val header = ByteArray(12)
        header[0] = query[0]
        header[1] = query[1]
        header[2] = 0x81.toByte()
        header[3] = 0x80.toByte()
        header[4] = 0
        header[5] = 1
        header[6] = (answers.size ushr 8).toByte()
        header[7] = (answers.size and 0xFF).toByte()

        val question = encodeName(name) + byteArrayOf(
            (qType ushr 8).toByte(), (qType and 0xFF).toByte(),
            (qClass ushr 8).toByte(), (qClass and 0xFF).toByte(),
        )
        val body = answers.fold(ByteArray(0)) { acc, a -> acc + a }
        return header + question + body
    }

    private fun encodeName(name: String): ByteArray {
        val parts = name.split('.').filter { it.isNotEmpty() }
        val out = ArrayList<Byte>()
        for (p in parts) {
            val b = p.toByteArray(Charsets.US_ASCII)
            out += b.size.toByte()
            out += b.toList()
        }
        out += 0
        return out.toByteArray()
    }

    private fun encodeAnswer(name: String, type: Int, qClass: Int, ttl: Int, rdata: ByteArray): ByteArray {
        val nameBytes = encodeName(name)
        val buf = ByteBuffer.allocate(nameBytes.size + 10 + rdata.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(nameBytes)
        buf.putShort(type.toShort())
        buf.putShort(qClass.toShort())
        buf.putInt(ttl)
        buf.putShort(rdata.size.toShort())
        buf.put(rdata)
        return buf.array()
    }
}

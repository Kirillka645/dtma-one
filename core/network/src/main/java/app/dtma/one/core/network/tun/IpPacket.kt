package app.dtma.one.core.network.tun

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class IpVersion { V4, V6 }

internal data class ParsedPacket(
    val version: IpVersion,
    val protocol: Int,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
    val raw: ByteArray,
    val headerLength: Int,
)

internal object IpPacketParser {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMP = 1
    const val PROTO_ICMPV6 = 58

    fun parse(raw: ByteArray, length: Int): ParsedPacket? {
        if (length < 1) return null
        val version = (raw[0].toInt() ushr 4) and 0xF
        return when (version) {
            4 -> parseV4(raw, length)
            6 -> parseV6(raw, length)
            else -> null
        }
    }

    private fun parseV4(raw: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null
        val ihl = (raw[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        val totalLength = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val usable = minOf(length, totalLength)
        val protocol = raw[9].toInt() and 0xFF
        val src = InetAddress.getByAddress(raw.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(raw.copyOfRange(16, 20))
        if (protocol != PROTO_TCP && protocol != PROTO_UDP) {
            return ParsedPacket(
                IpVersion.V4, protocol, src, dst, 0, 0,
                raw.copyOfRange(ihl, usable), raw.copyOf(usable), ihl,
            )
        }
        if (usable < ihl + 4) return null
        val srcPort = ((raw[ihl].toInt() and 0xFF) shl 8) or (raw[ihl + 1].toInt() and 0xFF)
        val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        val payload = raw.copyOfRange(ihl, usable)
        return ParsedPacket(IpVersion.V4, protocol, src, dst, srcPort, dstPort, payload, raw.copyOf(usable), ihl)
    }

    private fun parseV6(raw: ByteArray, length: Int): ParsedPacket? {
        if (length < 40) return null
        val payloadLength = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
        val nextHeader = raw[6].toInt() and 0xFF
        val src = InetAddress.getByAddress(raw.copyOfRange(8, 24))
        val dst = InetAddress.getByAddress(raw.copyOfRange(24, 40))
        val total = minOf(length, 40 + payloadLength)
        if (nextHeader != PROTO_TCP && nextHeader != PROTO_UDP) {
            return ParsedPacket(
                IpVersion.V6, nextHeader, src, dst, 0, 0,
                raw.copyOfRange(40, total), raw.copyOf(total), 40,
            )
        }
        if (total < 44) return null
        val srcPort = ((raw[40].toInt() and 0xFF) shl 8) or (raw[41].toInt() and 0xFF)
        val dstPort = ((raw[42].toInt() and 0xFF) shl 8) or (raw[43].toInt() and 0xFF)
        return ParsedPacket(
            IpVersion.V6, nextHeader, src, dst, srcPort, dstPort,
            raw.copyOfRange(40, total), raw.copyOf(total), 40,
        )
    }
}

internal object PacketBuilder {
    fun ipv4Udp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0) // id
        buf.putShort(0x4000.toShort()) // don't fragment
        buf.put(64)
        buf.put(IpPacketParser.PROTO_UDP.toByte())
        buf.putShort(0) // checksum placeholder
        buf.put(src)
        buf.put(dst)
        // UDP
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        buf.put(payload)
        val arr = buf.array()
        val ipChecksum = checksum(arr, 0, 20).toInt() and 0xFFFF
        arr[10] = (ipChecksum ushr 8).toByte()
        arr[11] = (ipChecksum and 0xFF).toByte()
        // UDP checksum with pseudo header
        val udpChecksum = udpChecksumV4(src, dst, arr, 20, udpLen).toInt() and 0xFFFF
        arr[26] = (udpChecksum ushr 8).toByte()
        arr[27] = (udpChecksum and 0xFF).toByte()
        return arr
    }

    fun ipv4TcpRst(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
    ): ByteArray {
        val total = 20 + 20
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64)
        buf.put(IpPacketParser.PROTO_TCP.toByte())
        buf.putShort(0)
        buf.put(src)
        buf.put(dst)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq)
        buf.putInt(ack)
        buf.put((5 shl 4).toByte()) // data offset
        buf.put(0x14.toByte()) // RST+ACK
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        val arr = buf.array()
        val ipChecksum = checksum(arr, 0, 20).toInt() and 0xFFFF
        arr[10] = (ipChecksum ushr 8).toByte()
        arr[11] = (ipChecksum and 0xFF).toByte()
        val tcpCs = tcpChecksumV4(src, dst, arr, 20, 20).toInt() and 0xFFFF
        arr[36] = (tcpCs ushr 8).toByte()
        arr[37] = (tcpCs and 0xFF).toByte()
        return arr
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun udpChecksumV4(src: ByteArray, dst: ByteArray, packet: ByteArray, udpOffset: Int, udpLen: Int): Short {
        var sum = 0L
        fun addBytes(b: ByteArray) {
            var i = 0
            while (i + 1 < b.size) {
                sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < b.size) sum += (b[i].toInt() and 0xFF) shl 8
        }
        addBytes(src)
        addBytes(dst)
        sum += IpPacketParser.PROTO_UDP
        sum += udpLen
        var i = udpOffset
        val end = udpOffset + udpLen
        // zero checksum field
        while (i + 1 < end) {
            if (i == udpOffset + 6) {
                i += 2
                continue
            }
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val result = (sum.inv() and 0xFFFF)
        return if (result == 0L) 0xFFFF.toShort() else result.toShort()
    }

    private fun tcpChecksumV4(src: ByteArray, dst: ByteArray, packet: ByteArray, tcpOffset: Int, tcpLen: Int): Short {
        var sum = 0L
        fun addBytes(b: ByteArray) {
            var i = 0
            while (i + 1 < b.size) {
                sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < b.size) sum += (b[i].toInt() and 0xFF) shl 8
        }
        addBytes(src)
        addBytes(dst)
        sum += IpPacketParser.PROTO_TCP
        sum += tcpLen
        var i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i + 1 < end) {
            if (i == tcpOffset + 16) {
                i += 2
                continue
            }
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toShort()
    }
}

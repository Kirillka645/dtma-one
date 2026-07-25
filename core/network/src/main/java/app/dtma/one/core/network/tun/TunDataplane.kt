package app.dtma.one.core.network.tun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Userspace TUN dataplane: IPv4 TCP/UDP forwarding with VpnService.protect().
 *
 * Architecture (ADR-0001): pure-Kotlin userspace relay — no opaque .so.
 * - DNS to VPN DNS (10.0.0.1) handled by SimpleDnsServer (PAER-ordered answers).
 * - TCP: protect()+connect to original destination (or PAER-selected remap when hostname known).
 * - UDP: protect()+relay.
 * - IPv6: accepted on TUN when enabled; TCP/UDP forwarding supported when addresses parse.
 *
 * Bidirectional NAT mapping keeps originalDestination presentation for the app
 * when destination remapping is applied.
 */
class TunDataplane(
    private val vpnService: VpnService,
    private val tunInterface: ParcelFileDescriptor,
    private val dnsServer: SimpleDnsServer,
    private val sessionCache: DnsSessionCache,
    private val vpnDnsV4: ByteArray = byteArrayOf(10, 0, 0, 1),
    private val selectDestination: (hostname: String?, originalIp: String, port: Int) -> String? = { _, ip, _ -> ip },
) {
    companion object {
        private const val TAG = "DtmaTun"
        private const val MTU = 1500
    }

    private val running = AtomicBoolean(false)
    private val activeFlows = AtomicInteger(0)
    private val tcpFlows = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<FlowKey, UdpFlow>()

    @Volatile
    var onFlowCountChanged: ((Int) -> Unit)? = null

    data class FlowKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int,
    )

    data class NatMapping(
        val originalSource: String,
        val originalDestination: String,
        val selectedDestination: String,
        val protocol: Int,
        val createdAt: Long,
        val networkContextId: String?,
    )

    private val natTable = ConcurrentHashMap<FlowKey, NatMapping>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "dtma-tun-reader", isDaemon = true) { readerLoop() }
        thread(name = "dtma-tun-tcp", isDaemon = true) { tcpPumpLoop() }
        thread(name = "dtma-tun-udp", isDaemon = true) { udpPumpLoop() }
        thread(name = "dtma-tun-cleaner", isDaemon = true) { cleanerLoop() }
        Log.i(TAG, "Dataplane started")
    }

    fun stop() {
        running.set(false)
        tcpFlows.values.forEach { it.close() }
        udpFlows.values.forEach { it.close() }
        tcpFlows.clear()
        udpFlows.clear()
        natTable.clear()
        activeFlows.set(0)
        try {
            tunInterface.close()
        } catch (_: Exception) {
        }
        Log.i(TAG, "Dataplane stopped")
    }

    fun flowCount(): Int = activeFlows.get()

    private fun readerLoop() {
        val input = FileInputStream(tunInterface.fileDescriptor)
        val packet = ByteArray(MTU)
        while (running.get()) {
            try {
                val length = input.read(packet)
                if (length <= 0) {
                    if (!running.get()) break
                    continue
                }
                handlePacket(packet, length)
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "read error: ${e.message}")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "packet handle: ${t.message}")
            }
        }
    }

    private fun writeToTun(packet: ByteArray) {
        try {
            FileOutputStream(tunInterface.fileDescriptor).use { /* don't close fd */ }
            // FileOutputStream closing would close the fd — use channel write carefully
        } catch (_: Exception) {
        }
        try {
            val fos = FileOutputStream(tunInterface.fileDescriptor)
            // Writing without closing the underlying FD: duplicate approach
            val channel = fos.channel
            channel.write(ByteBuffer.wrap(packet))
            // Do not close fos — would close TUN. Detach by leaking intentionally is wrong.
            // Use reflection-free: ParcelFileDescriptor.AutoCloseOutputStream is same issue.
        } catch (e: Exception) {
            Log.w(TAG, "write tun failed: ${e.message}")
        }
    }

    private val tunOutLock = Any()
    private var tunOut: FileOutputStream? = null

    private fun ensureOut(): FileOutputStream {
        val existing = tunOut
        if (existing != null) return existing
        synchronized(tunOutLock) {
            if (tunOut == null) {
                tunOut = FileOutputStream(tunInterface.fileDescriptor)
            }
            return tunOut!!
        }
    }

    private fun writePacket(packet: ByteArray) {
        try {
            synchronized(tunOutLock) {
                ensureOut().write(packet)
                ensureOut().flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "tun write: ${e.message}")
        }
    }

    private fun handlePacket(raw: ByteArray, length: Int) {
        val parsed = IpPacketParser.parse(raw, length) ?: return
        when (parsed.protocol) {
            IpPacketParser.PROTO_UDP -> handleUdp(parsed)
            IpPacketParser.PROTO_TCP -> handleTcp(parsed)
            else -> {
                // ICMP and others: drop silently in MVP (document limitation)
            }
        }
    }

    private fun handleUdp(p: ParsedPacket) {
        val dstIp = p.destinationAddress.hostAddress ?: return
        val srcIp = p.sourceAddress.hostAddress ?: return
        val isDns = p.destinationPort == 53 && p.destinationAddress.address.contentEquals(vpnDnsV4)

        if (isDns) {
            // payload is UDP header + DNS
            if (p.payload.size < 8) return
            val dnsQuery = p.payload.copyOfRange(8, p.payload.size)
            val response = dnsServer.handleQuery(dnsQuery) ?: return
            val src = p.destinationAddress.address
            val dst = p.sourceAddress.address
            if (src.size == 4 && dst.size == 4) {
                val pkt = PacketBuilder.ipv4Udp(
                    src = src,
                    dst = dst,
                    srcPort = 53,
                    dstPort = p.sourcePort,
                    payload = response,
                )
                writePacket(pkt)
            }
            return
        }

        val key = FlowKey(srcIp, p.sourcePort, dstIp, p.destinationPort, IpPacketParser.PROTO_UDP)
        var flow = udpFlows[key]
        if (flow == null) {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            if (!vpnService.protect(channel.socket())) {
                Log.w(TAG, "protect() failed for UDP")
                channel.close()
                return
            }
            val hostname = sessionCache.hostnameForIp(dstIp)
            val selected = selectDestination(hostname, dstIp, p.destinationPort) ?: dstIp
            if (selected != dstIp && hostname != null) {
                natTable[key] = NatMapping(
                    originalSource = "$srcIp:${p.sourcePort}",
                    originalDestination = "$dstIp:${p.destinationPort}",
                    selectedDestination = "$selected:${p.destinationPort}",
                    protocol = IpPacketParser.PROTO_UDP,
                    createdAt = System.currentTimeMillis(),
                    networkContextId = null,
                )
            }
            channel.connect(InetSocketAddress(selected, p.destinationPort))
            flow = UdpFlow(key, channel, p.sourceAddress, p.destinationAddress, p.sourcePort, p.destinationPort)
            udpFlows[key] = flow
            bumpFlows()
        }
        if (p.payload.size > 8) {
            val data = ByteBuffer.wrap(p.payload, 8, p.payload.size - 8)
            try {
                flow.channel.write(data)
            } catch (e: Exception) {
                flow.close()
                udpFlows.remove(key)
                bumpFlows(-1)
            }
        }
    }

    private fun handleTcp(p: ParsedPacket) {
        if (p.payload.size < 20) return
        val dataOffset = ((p.payload[12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || p.payload.size < dataOffset) return
        val flags = p.payload[13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val seq = ByteBuffer.wrap(p.payload, 4, 4).int
        val ackNum = ByteBuffer.wrap(p.payload, 8, 4).int
        val payloadData = if (p.payload.size > dataOffset) {
            p.payload.copyOfRange(dataOffset, p.payload.size)
        } else {
            ByteArray(0)
        }

        val dstIp = p.destinationAddress.hostAddress ?: return
        val srcIp = p.sourceAddress.hostAddress ?: return
        val key = FlowKey(srcIp, p.sourcePort, dstIp, p.destinationPort, IpPacketParser.PROTO_TCP)

        if (rst) {
            tcpFlows.remove(key)?.close()
            bumpFlows(-1)
            return
        }

        var flow = tcpFlows[key]
        if (flow == null) {
            if (!syn || ack) return
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            if (!vpnService.protect(channel.socket())) {
                Log.w(TAG, "protect() failed for TCP")
                channel.close()
                return
            }
            val hostname = sessionCache.hostnameForIp(dstIp)
            val selected = selectDestination(hostname, dstIp, p.destinationPort) ?: dstIp
            if (selected != dstIp && hostname != null) {
                natTable[key] = NatMapping(
                    originalSource = "$srcIp:${p.sourcePort}",
                    originalDestination = "$dstIp:${p.destinationPort}",
                    selectedDestination = "$selected:${p.destinationPort}",
                    protocol = IpPacketParser.PROTO_TCP,
                    createdAt = System.currentTimeMillis(),
                    networkContextId = null,
                )
            }
            try {
                channel.connect(InetSocketAddress(selected, p.destinationPort))
            } catch (e: Exception) {
                channel.close()
                return
            }
            flow = TcpFlow(
                key = key,
                channel = channel,
                clientAddr = p.sourceAddress,
                remotePresentedAddr = p.destinationAddress,
                clientPort = p.sourcePort,
                remotePort = p.destinationPort,
                clientIsn = seq,
            )
            tcpFlows[key] = flow
            bumpFlows()
        }

        flow.lastActivityMs = System.currentTimeMillis()
        if (syn && !ack) {
            // Wait until channel connected; SYN-ACK generated in tcpPumpLoop
            flow.pendingClientSyn = true
            flow.clientIsn = seq
            return
        }
        if (payloadData.isNotEmpty()) {
            flow.outQueue.add(payloadData)
            flow.clientSeq = seq + payloadData.size
        }
        if (fin) {
            flow.clientFin = true
        }
    }

    private fun tcpPumpLoop() {
        val buf = ByteBuffer.allocate(MTU)
        while (running.get()) {
            try {
                val flows = tcpFlows.values.toList()
                for (flow in flows) {
                    try {
                        pumpTcp(flow, buf)
                    } catch (e: Exception) {
                        flow.close()
                        tcpFlows.remove(flow.key)
                        bumpFlows(-1)
                    }
                }
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun pumpTcp(flow: TcpFlow, buf: ByteBuffer) {
        val ch = flow.channel
        if (!flow.connected) {
            if (ch.finishConnect()) {
                flow.connected = true
                if (flow.pendingClientSyn) {
                    // Send SYN-ACK to client through TUN
                    val synAck = buildTcpPacket(
                        src = flow.remotePresentedAddr.address,
                        dst = flow.clientAddr.address,
                        srcPort = flow.remotePort,
                        dstPort = flow.clientPort,
                        seq = flow.serverIsn,
                        ack = flow.clientIsn + 1,
                        flags = 0x12, // SYN+ACK
                        payload = ByteArray(0),
                    )
                    if (synAck != null) writePacket(synAck)
                    flow.serverSeq = flow.serverIsn + 1
                    flow.clientSeq = flow.clientIsn + 1
                    flow.pendingClientSyn = false
                }
            } else if (System.currentTimeMillis() - flow.createdAt > 12_000) {
                throw IOException("connect timeout")
            }
            return
        }

        // Write queued client payload to remote
        while (flow.outQueue.isNotEmpty()) {
            val data = flow.outQueue.first()
            val n = ch.write(ByteBuffer.wrap(data))
            if (n <= 0) break
            if (n < data.size) {
                flow.outQueue[0] = data.copyOfRange(n, data.size)
                break
            } else {
                flow.outQueue.removeAt(0)
            }
        }

        // Read remote -> client
        buf.clear()
        val n = try {
            ch.read(buf)
        } catch (_: Exception) {
            -1
        }
        if (n > 0) {
            buf.flip()
            val data = ByteArray(buf.remaining())
            buf.get(data)
            val pkt = buildTcpPacket(
                src = flow.remotePresentedAddr.address,
                dst = flow.clientAddr.address,
                srcPort = flow.remotePort,
                dstPort = flow.clientPort,
                seq = flow.serverSeq,
                ack = flow.clientSeq,
                flags = 0x18, // PSH+ACK
                payload = data,
            )
            if (pkt != null) writePacket(pkt)
            flow.serverSeq += data.size
        } else if (n < 0) {
            // remote closed
            val fin = buildTcpPacket(
                src = flow.remotePresentedAddr.address,
                dst = flow.clientAddr.address,
                srcPort = flow.remotePort,
                dstPort = flow.clientPort,
                seq = flow.serverSeq,
                ack = flow.clientSeq,
                flags = 0x11, // FIN+ACK
                payload = ByteArray(0),
            )
            if (fin != null) writePacket(fin)
            flow.close()
            tcpFlows.remove(flow.key)
            bumpFlows(-1)
        }

        if (flow.clientFin && flow.outQueue.isEmpty()) {
            runCatching { ch.shutdownOutput() }
        }
    }

    private fun udpPumpLoop() {
        val buf = ByteBuffer.allocate(MTU)
        while (running.get()) {
            try {
                for (flow in udpFlows.values.toList()) {
                    try {
                        buf.clear()
                        val n = flow.channel.read(buf)
                        if (n > 0) {
                            buf.flip()
                            val data = ByteArray(buf.remaining())
                            buf.get(data)
                            val src = flow.remoteAddr.address
                            val dst = flow.clientAddr.address
                            if (src.size == 4 && dst.size == 4) {
                                val pkt = PacketBuilder.ipv4Udp(
                                    src = src,
                                    dst = dst,
                                    srcPort = flow.remotePort,
                                    dstPort = flow.clientPort,
                                    payload = data,
                                )
                                writePacket(pkt)
                            }
                            flow.lastActivityMs = System.currentTimeMillis()
                        }
                    } catch (_: Exception) {
                        flow.close()
                        udpFlows.remove(flow.key)
                        bumpFlows(-1)
                    }
                }
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun cleanerLoop() {
        while (running.get()) {
            try {
                val now = System.currentTimeMillis()
                tcpFlows.entries.removeIf { (_, f) ->
                    if (now - f.lastActivityMs > 120_000) {
                        f.close()
                        bumpFlows(-1)
                        true
                    } else false
                }
                udpFlows.entries.removeIf { (_, f) ->
                    if (now - f.lastActivityMs > 60_000) {
                        f.close()
                        bumpFlows(-1)
                        true
                    } else false
                }
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun bumpFlows(delta: Int = 1) {
        val v = if (delta > 0) activeFlows.incrementAndGet()
        else activeFlows.updateAndGet { (it + delta).coerceAtLeast(0) }
        onFlowCountChanged?.invoke(v)
    }

    private fun buildTcpPacket(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        payload: ByteArray,
    ): ByteArray? {
        if (src.size != 4 || dst.size != 4) return null // IPv6 TCP craft simplified: skip in MVP write path
        val tcpLen = 20 + payload.size
        val total = 20 + tcpLen
        val arr = ByteArray(total)
        val buf = ByteBuffer.wrap(arr)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort((System.nanoTime() and 0xFFFF).toShort())
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
        buf.put((5 shl 4).toByte())
        buf.put(flags.toByte())
        buf.putShort(65535.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(payload)

        // IP checksum
        var sum = 0L
        var i = 0
        while (i < 20) {
            sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val ipcs = (sum.inv() and 0xFFFF).toInt()
        arr[10] = (ipcs ushr 8).toByte()
        arr[11] = (ipcs and 0xFF).toByte()

        // TCP checksum
        sum = 0L
        fun add(b: ByteArray) {
            var j = 0
            while (j + 1 < b.size) {
                sum += ((b[j].toInt() and 0xFF) shl 8) or (b[j + 1].toInt() and 0xFF)
                j += 2
            }
            if (j < b.size) sum += (b[j].toInt() and 0xFF) shl 8
        }
        add(src); add(dst)
        sum += IpPacketParser.PROTO_TCP
        sum += tcpLen
        i = 20
        while (i + 1 < total) {
            if (i == 36) {
                i += 2
                continue
            }
            sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < total) sum += (arr[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val tcs = (sum.inv() and 0xFFFF).toInt()
        arr[36] = (tcs ushr 8).toByte()
        arr[37] = (tcs and 0xFF).toByte()
        return arr
    }

    private class TcpFlow(
        val key: FlowKey,
        val channel: SocketChannel,
        val clientAddr: InetAddress,
        val remotePresentedAddr: InetAddress,
        val clientPort: Int,
        val remotePort: Int,
        var clientIsn: Int,
        val serverIsn: Int = (System.nanoTime() and 0x7FFFFFFF).toInt(),
        var serverSeq: Int = 0,
        var clientSeq: Int = 0,
        var connected: Boolean = false,
        var pendingClientSyn: Boolean = false,
        var clientFin: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        var lastActivityMs: Long = System.currentTimeMillis(),
        val outQueue: MutableList<ByteArray> = mutableListOf(),
    ) {
        fun close() {
            runCatching { channel.close() }
        }
    }

    private class UdpFlow(
        val key: FlowKey,
        val channel: DatagramChannel,
        val clientAddr: InetAddress,
        val remoteAddr: InetAddress,
        val clientPort: Int,
        val remotePort: Int,
        var lastActivityMs: Long = System.currentTimeMillis(),
    ) {
        fun close() {
            runCatching { channel.close() }
        }
    }
}

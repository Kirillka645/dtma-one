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
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Userspace TUN dataplane: IPv4 TCP/UDP/DNS with VpnService.protect().
 *
 * Critical design points:
 * - DNS is handled off the TUN reader thread (blocking upstream DNS must not stall the stack).
 * - TCP sends proper ACKs to the client; without them Android sockets stall (Telegram, browsers).
 * - IPv6 is not claimed unless a full IPv6 path exists (otherwise traffic is black-holed).
 */
class TunDataplane(
    private val vpnService: VpnService,
    private val tunInterface: ParcelFileDescriptor,
    private val dnsServer: SimpleDnsServer,
    private val sessionCache: DnsSessionCache,
    private val vpnDnsV4: ByteArray = byteArrayOf(10, 0, 0, 1),
    private val selectDestination: (hostname: String?, originalIp: String, port: Int) -> String? =
        { _, ip, _ -> ip },
) {
    companion object {
        private const val TAG = "DtmaTun"
        private const val MTU = 1500
        private const val TCP_IDLE_MS = 180_000L
        private const val UDP_IDLE_MS = 90_000L
    }

    private val running = AtomicBoolean(false)
    private val activeFlows = AtomicInteger(0)
    private val tcpFlows = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<FlowKey, UdpFlow>()
    private val dnsExecutor = Executors.newFixedThreadPool(2)
    private val outboundQueue = LinkedBlockingQueue<ByteArray>(512)

    @Volatile
    var onFlowCountChanged: ((Int) -> Unit)? = null

    data class FlowKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int,
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "dtma-tun-reader", isDaemon = true) { readerLoop() }
        thread(name = "dtma-tun-writer", isDaemon = true) { writerLoop() }
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
        activeFlows.set(0)
        dnsExecutor.shutdownNow()
        outboundQueue.clear()
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
                handlePacket(packet.copyOf(length), length)
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "read error: ${e.message}")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "packet handle: ${t.message}")
            }
        }
    }

    private fun writerLoop() {
        val out = FileOutputStream(tunInterface.fileDescriptor)
        while (running.get()) {
            try {
                val pkt = outboundQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(pkt)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tun write: ${e.message}")
            }
        }
    }

    private fun writePacket(packet: ByteArray) {
        if (!outboundQueue.offer(packet)) {
            // Drop oldest to make room for control packets
            outboundQueue.poll()
            outboundQueue.offer(packet)
        }
    }

    private fun handlePacket(raw: ByteArray, length: Int) {
        val parsed = IpPacketParser.parse(raw, length) ?: return
        when (parsed.protocol) {
            IpPacketParser.PROTO_UDP -> handleUdp(parsed)
            IpPacketParser.PROTO_TCP -> handleTcp(parsed)
            else -> Unit // ICMP etc. not implemented
        }
    }

    private fun handleUdp(p: ParsedPacket) {
        if (p.version != IpVersion.V4) return // IPv6 UDP not implemented
        val dstIp = p.destinationAddress.hostAddress ?: return
        val srcIp = p.sourceAddress.hostAddress ?: return
        val isDns = p.destinationPort == 53 &&
            p.destinationAddress.address.contentEquals(vpnDnsV4)

        if (isDns) {
            if (p.payload.size < 8) return
            val dnsQuery = p.payload.copyOfRange(8, p.payload.size)
            val clientAddr = p.sourceAddress.address
            val clientPort = p.sourcePort
            dnsExecutor.execute {
                try {
                    val response = dnsServer.handleQuery(dnsQuery) ?: return@execute
                    val pkt = PacketBuilder.ipv4Udp(
                        src = vpnDnsV4,
                        dst = clientAddr,
                        srcPort = 53,
                        dstPort = clientPort,
                        payload = response,
                    )
                    writePacket(pkt)
                } catch (e: Exception) {
                    Log.w(TAG, "dns handle: ${e.message}")
                }
            }
            return
        }

        val key = FlowKey(srcIp, p.sourcePort, dstIp, p.destinationPort, IpPacketParser.PROTO_UDP)
        var flow = udpFlows[key]
        if (flow == null) {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            if (!vpnService.protect(channel.socket())) {
                Log.w(TAG, "protect() failed for UDP $dstIp:${p.destinationPort}")
                channel.close()
                return
            }
            val hostname = sessionCache.hostnameForIp(dstIp)
            val selected = selectDestination(hostname, dstIp, p.destinationPort) ?: dstIp
            try {
                channel.connect(InetSocketAddress(selected, p.destinationPort))
            } catch (e: Exception) {
                channel.close()
                return
            }
            flow = UdpFlow(
                key, channel, p.sourceAddress, p.destinationAddress,
                p.sourcePort, p.destinationPort,
            )
            udpFlows[key] = flow
            bumpFlows(+1)
        }
        if (p.payload.size > 8) {
            val data = ByteBuffer.wrap(p.payload, 8, p.payload.size - 8)
            try {
                flow.channel.write(data)
                flow.lastActivityMs = System.currentTimeMillis()
            } catch (_: Exception) {
                flow.close()
                if (udpFlows.remove(key) != null) bumpFlows(-1)
            }
        }
    }

    private fun handleTcp(p: ParsedPacket) {
        if (p.version != IpVersion.V4) return
        if (p.payload.size < 20) return
        val dataOffset = ((p.payload[12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || p.payload.size < dataOffset) return
        val flags = p.payload[13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val ackFlag = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val seq = readInt(p.payload, 4)
        val payloadData = if (p.payload.size > dataOffset) {
            p.payload.copyOfRange(dataOffset, p.payload.size)
        } else {
            ByteArray(0)
        }

        val dstIp = p.destinationAddress.hostAddress ?: return
        val srcIp = p.sourceAddress.hostAddress ?: return
        val key = FlowKey(srcIp, p.sourcePort, dstIp, p.destinationPort, IpPacketParser.PROTO_TCP)

        if (rst) {
            tcpFlows.remove(key)?.let {
                it.close()
                bumpFlows(-1)
            }
            return
        }

        var flow = tcpFlows[key]
        if (flow == null) {
            // Only accept new flows on pure SYN
            if (!syn || ackFlag) return
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            if (!vpnService.protect(channel.socket())) {
                Log.w(TAG, "protect() failed for TCP $dstIp:${p.destinationPort}")
                channel.close()
                return
            }
            val hostname = sessionCache.hostnameForIp(dstIp)
            val selected = selectDestination(hostname, dstIp, p.destinationPort) ?: dstIp
            try {
                channel.connect(InetSocketAddress(selected, p.destinationPort))
            } catch (e: Exception) {
                Log.w(TAG, "connect start failed $selected:${p.destinationPort}: ${e.message}")
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
                clientNextSeq = seq + 1, // SYN consumes 1
            )
            tcpFlows[key] = flow
            bumpFlows(+1)
            Log.d(TAG, "TCP SYN $srcIp:${p.sourcePort} -> $selected:${p.destinationPort} (presented $dstIp)")
            return
        }

        flow.lastActivityMs = System.currentTimeMillis()

        // Retransmitted SYN while still connecting
        if (syn && !ackFlag && !flow.connected) {
            flow.clientIsn = seq
            flow.clientNextSeq = seq + 1
            flow.pendingClientSyn = true
            return
        }

        // After handshake: accept in-order data and always ACK progress.
        if (flow.established) {
            if (payloadData.isNotEmpty()) {
                val expected = flow.clientNextSeq
                when {
                    seq == expected -> {
                        flow.outQueue.add(payloadData)
                        flow.clientNextSeq = expected + payloadData.size
                        flow.needAck = true
                    }
                    seqLess(seq, expected) -> {
                        // duplicate — re-ACK
                        flow.needAck = true
                    }
                    else -> {
                        // out-of-order: ACK what we have (simple stack)
                        flow.needAck = true
                    }
                }
            } else if (ackFlag) {
                // pure ACK from client; nothing to queue
            }
            if (fin) {
                // FIN consumes 1 sequence number if this is a new FIN
                if (!flow.clientFin) {
                    if (payloadData.isEmpty() && seq == flow.clientNextSeq) {
                        flow.clientNextSeq += 1
                    } else if (payloadData.isNotEmpty()) {
                        // FIN with data already advanced clientNextSeq by payload size; +1 for FIN
                        flow.clientNextSeq += 1
                    }
                    flow.clientFin = true
                    flow.needAck = true
                }
            }
        }
    }

    private fun tcpPumpLoop() {
        val buf = ByteBuffer.allocate(MTU)
        while (running.get()) {
            try {
                for (flow in tcpFlows.values.toList()) {
                    try {
                        pumpTcp(flow, buf)
                    } catch (e: Exception) {
                        Log.d(TAG, "tcp flow end ${flow.key}: ${e.message}")
                        flow.close()
                        if (tcpFlows.remove(flow.key) != null) bumpFlows(-1)
                    }
                }
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun pumpTcp(flow: TcpFlow, buf: ByteBuffer) {
        val ch = flow.channel
        if (!flow.connected) {
            try {
                if (ch.finishConnect()) {
                    flow.connected = true
                    flow.established = true
                    // SYN-ACK to client
                    sendTcp(
                        flow,
                        seq = flow.serverIsn,
                        ack = flow.clientNextSeq,
                        flags = 0x12, // SYN+ACK
                        payload = ByteArray(0),
                    )
                    flow.serverNextSeq = flow.serverIsn + 1
                    flow.pendingClientSyn = false
                    Log.d(TAG, "TCP ESTABLISHED ${flow.key.dstIp}:${flow.key.dstPort}")
                } else if (System.currentTimeMillis() - flow.createdAt > 15_000) {
                    throw IOException("connect timeout")
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("connect failed: ${e.message}", e)
            }
            return
        }

        // Flush client->remote
        while (flow.outQueue.isNotEmpty()) {
            val data = flow.outQueue.first()
            val n = try {
                ch.write(ByteBuffer.wrap(data))
            } catch (e: Exception) {
                throw IOException("remote write: ${e.message}", e)
            }
            if (n <= 0) break
            if (n < data.size) {
                flow.outQueue[0] = data.copyOfRange(n, data.size)
                break
            } else {
                flow.outQueue.removeAt(0)
            }
        }

        // ACK client progress if needed (critical for Telegram / browsers)
        if (flow.needAck) {
            sendTcp(
                flow,
                seq = flow.serverNextSeq,
                ack = flow.clientNextSeq,
                flags = 0x10, // ACK
                payload = ByteArray(0),
            )
            flow.needAck = false
        }

        // remote -> client
        buf.clear()
        val n = try {
            ch.read(buf)
        } catch (e: Exception) {
            -1
        }
        when {
            n > 0 -> {
                buf.flip()
                val data = ByteArray(buf.remaining())
                buf.get(data)
                sendTcp(
                    flow,
                    seq = flow.serverNextSeq,
                    ack = flow.clientNextSeq,
                    flags = 0x18, // PSH+ACK
                    payload = data,
                )
                flow.serverNextSeq += data.size
                flow.lastActivityMs = System.currentTimeMillis()
            }
            n < 0 -> {
                // remote closed
                sendTcp(
                    flow,
                    seq = flow.serverNextSeq,
                    ack = flow.clientNextSeq,
                    flags = 0x11, // FIN+ACK
                    payload = ByteArray(0),
                )
                flow.serverNextSeq += 1
                flow.close()
                if (tcpFlows.remove(flow.key) != null) bumpFlows(-1)
                return
            }
        }

        if (flow.clientFin && flow.outQueue.isEmpty()) {
            runCatching { ch.shutdownOutput() }
        }
    }

    private fun sendTcp(
        flow: TcpFlow,
        seq: Int,
        ack: Int,
        flags: Int,
        payload: ByteArray,
    ) {
        val src = flow.remotePresentedAddr.address
        val dst = flow.clientAddr.address
        if (src.size != 4 || dst.size != 4) return
        val pkt = buildTcpPacket(
            src = src,
            dst = dst,
            srcPort = flow.remotePort,
            dstPort = flow.clientPort,
            seq = seq,
            ack = ack,
            flags = flags,
            payload = payload,
        ) ?: return
        writePacket(pkt)
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
                                writePacket(
                                    PacketBuilder.ipv4Udp(
                                        src = src,
                                        dst = dst,
                                        srcPort = flow.remotePort,
                                        dstPort = flow.clientPort,
                                        payload = data,
                                    ),
                                )
                            }
                            flow.lastActivityMs = System.currentTimeMillis()
                        }
                    } catch (_: Exception) {
                        flow.close()
                        if (udpFlows.remove(flow.key) != null) bumpFlows(-1)
                    }
                }
                Thread.sleep(1)
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
                    if (now - f.lastActivityMs > TCP_IDLE_MS) {
                        f.close()
                        bumpFlows(-1)
                        true
                    } else {
                        false
                    }
                }
                udpFlows.entries.removeIf { (_, f) ->
                    if (now - f.lastActivityMs > UDP_IDLE_MS) {
                        f.close()
                        bumpFlows(-1)
                        true
                    } else {
                        false
                    }
                }
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun bumpFlows(delta: Int) {
        val v = activeFlows.updateAndGet { cur -> (cur + delta).coerceAtLeast(0) }
        onFlowCountChanged?.invoke(v)
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** RFC 1982 serial number comparison for 32-bit TCP sequence numbers. */
    private fun seqLess(a: Int, b: Int): Boolean = (a - b) < 0

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
        if (src.size != 4 || dst.size != 4) return null
        val tcpLen = 20 + payload.size
        val total = 20 + tcpLen
        val arr = ByteArray(total)
        val buf = ByteBuffer.wrap(arr)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort((System.nanoTime() and 0xFFFF).toInt().toShort())
        buf.putShort(0x4000.toShort()) // DF
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

        // TCP checksum with pseudo-header
        sum = 0L
        fun add16(hi: Int, lo: Int) {
            sum += ((hi and 0xFF) shl 8) or (lo and 0xFF)
        }
        for (j in 0 until 4 step 2) {
            add16(src[j].toInt(), src[j + 1].toInt())
            add16(dst[j].toInt(), dst[j + 1].toInt())
        }
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
        var clientNextSeq: Int = 0,
        var serverNextSeq: Int = 0,
        var connected: Boolean = false,
        var established: Boolean = false,
        var pendingClientSyn: Boolean = true,
        var clientFin: Boolean = false,
        var needAck: Boolean = false,
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

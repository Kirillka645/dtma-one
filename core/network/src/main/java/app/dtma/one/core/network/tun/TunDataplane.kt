package app.dtma.one.core.network.tun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Userspace IPv4 TUN dataplane.
 *
 * Design choices that fix "no website loads":
 * 1. Separate TUN read/write FDs via [ParcelFileDescriptor.dup] (same-FD R/W deadlocks on many devices).
 * 2. Blocking outbound TCP sockets with [VpnService.protect] (reliable connect).
 * 3. Always ACK client TCP data (without ACK, Android TCP stack stalls forever).
 * 4. DNS handled off the reader thread; never block TUN read on network I/O.
 * 5. Cap TCP payload so IP packet stays under MTU.
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
        private const val MAX_TCP_PAYLOAD = 1360
        private const val TCP_CONNECT_TIMEOUT_MS = 12_000
        private const val TCP_IDLE_MS = 180_000L
        private const val UDP_IDLE_MS = 90_000L
    }

    private val running = AtomicBoolean(false)
    private val activeFlows = AtomicInteger(0)
    private val tcpFlows = ConcurrentHashMap<FlowKey, TcpBridge>()
    private val udpFlows = ConcurrentHashMap<FlowKey, UdpBridge>()
    private val connectPool = Executors.newCachedThreadPool()
    private val dnsPool = Executors.newFixedThreadPool(2)
    private val outbound = LinkedBlockingQueue<ByteArray>(1024)

    private var writePfd: ParcelFileDescriptor? = null

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
        writePfd = try {
            ParcelFileDescriptor.dup(tunInterface.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "dup tun fd failed, falling back to shared fd: ${e.message}")
            null
        }
        thread(name = "dtma-tun-reader", isDaemon = true) { readerLoop() }
        thread(name = "dtma-tun-writer", isDaemon = true) { writerLoop() }
        thread(name = "dtma-tun-udp-pump", isDaemon = true) { udpPumpLoop() }
        thread(name = "dtma-tun-cleaner", isDaemon = true) { cleanerLoop() }
        Log.i(TAG, "Dataplane started (writeFdDup=${writePfd != null})")
    }

    fun stop() {
        running.set(false)
        tcpFlows.values.forEach { it.close() }
        udpFlows.values.forEach { it.close() }
        tcpFlows.clear()
        udpFlows.clear()
        activeFlows.set(0)
        outbound.clear()
        connectPool.shutdownNow()
        dnsPool.shutdownNow()
        try {
            writePfd?.close()
        } catch (_: Exception) {
        }
        writePfd = null
        try {
            tunInterface.close()
        } catch (_: Exception) {
        }
        Log.i(TAG, "Dataplane stopped")
    }

    fun flowCount(): Int = activeFlows.get()

    private fun readerLoop() {
        val input = FileInputStream(tunInterface.fileDescriptor)
        val buf = ByteArray(MTU)
        while (running.get()) {
            try {
                val n = input.read(buf)
                if (n <= 0) {
                    if (!running.get()) break
                    continue
                }
                dispatch(buf.copyOf(n))
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "tun read: ${e.message}")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch: ${t.message}")
            }
        }
    }

    private fun writerLoop() {
        val fd = writePfd?.fileDescriptor ?: tunInterface.fileDescriptor
        val out = FileOutputStream(fd)
        while (running.get()) {
            try {
                val pkt = outbound.poll(250, TimeUnit.MILLISECONDS) ?: continue
                out.write(pkt)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tun write: ${e.message}")
            }
        }
    }

    private fun emit(packet: ByteArray) {
        if (!outbound.offer(packet)) {
            outbound.poll()
            outbound.offer(packet)
        }
    }

    private fun dispatch(raw: ByteArray) {
        val p = IpPacketParser.parse(raw, raw.size) ?: return
        if (p.version != IpVersion.V4) return
        when (p.protocol) {
            IpPacketParser.PROTO_UDP -> onUdp(p)
            IpPacketParser.PROTO_TCP -> onTcp(p)
        }
    }

    // ---------------- DNS / UDP ----------------

    private fun onUdp(p: ParsedPacket) {
        val dstIp = p.destinationAddress.hostAddress ?: return
        val srcIp = p.sourceAddress.hostAddress ?: return
        val toDns = p.destinationPort == 53 && p.destinationAddress.address.contentEquals(vpnDnsV4)
        if (toDns) {
            if (p.payload.size < 8) return
            val query = p.payload.copyOfRange(8, p.payload.size)
            val client = p.sourceAddress.address
            val cport = p.sourcePort
            dnsPool.execute {
                try {
                    val resp = dnsServer.handleQuery(query)
                    if (resp != null) {
                        emit(
                            PacketBuilder.ipv4Udp(
                                src = vpnDnsV4,
                                dst = client,
                                srcPort = 53,
                                dstPort = cport,
                                payload = resp,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "dns: ${e.message}")
                }
            }
            return
        }

        val key = FlowKey(srcIp, p.sourcePort, dstIp, p.destinationPort, IpPacketParser.PROTO_UDP)
        var bridge = udpFlows[key]
        if (bridge == null) {
            val ch = DatagramChannel.open()
            ch.configureBlocking(false)
            if (!vpnService.protect(ch.socket())) {
                Log.w(TAG, "UDP protect failed $dstIp:${p.destinationPort}")
                ch.close()
                return
            }
            val host = sessionCache.hostnameForIp(dstIp)
            val selected = selectDestination(host, dstIp, p.destinationPort) ?: dstIp
            try {
                ch.connect(InetSocketAddress(selected, p.destinationPort))
            } catch (e: Exception) {
                ch.close()
                return
            }
            bridge = UdpBridge(key, ch, p.sourceAddress, p.destinationAddress, p.sourcePort, p.destinationPort)
            udpFlows[key] = bridge
            bump(+1)
        }
        if (p.payload.size > 8) {
            try {
                bridge.channel.write(ByteBuffer.wrap(p.payload, 8, p.payload.size - 8))
                bridge.touch()
            } catch (_: Exception) {
                bridge.close()
                if (udpFlows.remove(key) != null) bump(-1)
            }
        }
    }

    private fun udpPumpLoop() {
        val buf = ByteBuffer.allocate(MTU)
        while (running.get()) {
            try {
                for (b in udpFlows.values.toList()) {
                    try {
                        buf.clear()
                        val n = b.channel.read(buf)
                        if (n > 0) {
                            buf.flip()
                            val data = ByteArray(buf.remaining())
                            buf.get(data)
                            val src = b.remote.address
                            val dst = b.client.address
                            if (src.size == 4 && dst.size == 4) {
                                emit(
                                    PacketBuilder.ipv4Udp(
                                        src = src,
                                        dst = dst,
                                        srcPort = b.remotePort,
                                        dstPort = b.clientPort,
                                        payload = data,
                                    ),
                                )
                            }
                            b.touch()
                        }
                    } catch (_: Exception) {
                        b.close()
                        if (udpFlows.remove(b.key) != null) bump(-1)
                    }
                }
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ---------------- TCP ----------------

    private fun onTcp(p: ParsedPacket) {
        if (p.payload.size < 20) return
        val doff = ((p.payload[12].toInt() and 0xF0) ushr 4) * 4
        if (doff < 20 || p.payload.size < doff) return
        val flags = p.payload[13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val seq = beInt(p.payload, 4)
        val payload = if (p.payload.size > doff) p.payload.copyOfRange(doff, p.payload.size) else ByteArray(0)

        val dstIp = p.destinationAddress.hostAddress ?: return
        val srcIp = p.sourceAddress.hostAddress ?: return
        val key = FlowKey(srcIp, p.sourcePort, dstIp, p.destinationPort, IpPacketParser.PROTO_TCP)

        if (rst) {
            tcpFlows.remove(key)?.let {
                it.close()
                bump(-1)
            }
            return
        }

        var bridge = tcpFlows[key]
        if (bridge == null) {
            if (!(syn && !ack)) return
            val host = sessionCache.hostnameForIp(dstIp)
            val selected = selectDestination(host, dstIp, p.destinationPort) ?: dstIp
            bridge = TcpBridge(
                key = key,
                clientAddr = p.sourceAddress,
                remotePresented = p.destinationAddress,
                clientPort = p.sourcePort,
                remotePort = p.destinationPort,
                clientIsn = seq,
                selectedIp = selected,
            )
            tcpFlows[key] = bridge
            bump(+1)
            Log.i(TAG, "TCP SYN $srcIp:${p.sourcePort} -> $selected:${p.destinationPort} (presented as $dstIp)")
            connectPool.execute { openTcp(bridge) }
            return
        }

        bridge.touch()
        if (!bridge.up.get()) {
            // still connecting; ignore non-SYN (or accept retrans SYN)
            if (syn && !ack) {
                bridge.clientIsn = seq
                bridge.clientNext.set(seq + 1)
            }
            return
        }

        // ESTABLISHED path
        if (payload.isNotEmpty()) {
            val expect = bridge.clientNext.get()
            when {
                seq == expect -> {
                    // deliver once
                    if (bridge.writeToRemote(payload)) {
                        bridge.clientNext.addAndGet(payload.size)
                    }
                    bridge.ackClient()
                }
                seqLt(seq, expect) -> bridge.ackClient() // duplicate
                else -> bridge.ackClient() // OOO: simple stack, just ACK current
            }
        }
        if (fin) {
            val expect = bridge.clientNext.get()
            if (seq == expect || seq == expect - payload.size || payload.isEmpty() && seq == expect) {
                if (payload.isEmpty() && seq == expect) {
                    bridge.clientNext.incrementAndGet()
                } else if (payload.isNotEmpty() && seq == expect - payload.size) {
                    // FIN with data already counted payload; add FIN
                    bridge.clientNext.incrementAndGet()
                } else if (payload.isEmpty()) {
                    // already advanced?
                }
            }
            bridge.ackClient()
            bridge.onClientFin()
        }
    }

    private fun openTcp(b: TcpBridge) {
        val sock = Socket()
        try {
            if (!vpnService.protect(sock)) {
                Log.w(TAG, "TCP protect failed ${b.selectedIp}:${b.remotePort}")
                failTcp(b)
                return
            }
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 0
            sock.connect(InetSocketAddress(b.selectedIp, b.remotePort), TCP_CONNECT_TIMEOUT_MS)
            b.socket = sock
            b.clientNext.set(b.clientIsn + 1)
            b.serverNext.set(b.serverIsn + 1)
            // SYN-ACK
            emitTcp(
                b,
                seq = b.serverIsn,
                ack = b.clientIsn + 1,
                flags = 0x12,
                payload = ByteArray(0),
                mss = true,
            )
            b.up.set(true)
            Log.i(TAG, "TCP UP ${b.selectedIp}:${b.remotePort}")
            // remote -> client pump
            val buf = ByteArray(MAX_TCP_PAYLOAD)
            val input = sock.getInputStream()
            while (running.get() && !b.closed.get()) {
                val n = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                val data = buf.copyOf(n)
                val seq = b.serverNext.getAndAdd(n)
                emitTcp(b, seq = seq, ack = b.clientNext.get(), flags = 0x18, payload = data, mss = false)
                b.touch()
            }
            // remote closed
            if (!b.closed.get()) {
                val seq = b.serverNext.getAndIncrement()
                emitTcp(b, seq = seq, ack = b.clientNext.get(), flags = 0x11, payload = ByteArray(0), mss = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP connect/pump ${b.selectedIp}:${b.remotePort}: ${e.message}")
            failTcp(b)
            return
        } finally {
            b.close()
            if (tcpFlows.remove(b.key) != null) bump(-1)
        }
    }

    private fun failTcp(b: TcpBridge) {
        // RST to client
        try {
            emitTcp(
                b,
                seq = 0,
                ack = b.clientIsn + 1,
                flags = 0x14, // RST+ACK
                payload = ByteArray(0),
                mss = false,
            )
        } catch (_: Exception) {
        }
        b.close()
        if (tcpFlows.remove(b.key) != null) bump(-1)
    }

    private fun emitTcp(
        b: TcpBridge,
        seq: Int,
        ack: Int,
        flags: Int,
        payload: ByteArray,
        mss: Boolean,
    ) {
        val src = b.remotePresented.address
        val dst = b.clientAddr.address
        if (src.size != 4 || dst.size != 4) return
        val pkt = buildIpv4Tcp(
            src = src,
            dst = dst,
            srcPort = b.remotePort,
            dstPort = b.clientPort,
            seq = seq,
            ack = ack,
            flags = flags,
            payload = payload,
            withMss = mss,
        )
        emit(pkt)
    }

    private fun TcpBridge.ackClient() {
        if (!up.get() || closed.get()) return
        emitTcp(
            this,
            seq = serverNext.get(),
            ack = clientNext.get(),
            flags = 0x10,
            payload = ByteArray(0),
            mss = false,
        )
    }

    private fun TcpBridge.writeToRemote(data: ByteArray): Boolean {
        val s = socket ?: return false
        return try {
            s.getOutputStream().write(data)
            s.getOutputStream().flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "tcp write remote: ${e.message}")
            false
        }
    }

    private fun TcpBridge.onClientFin() {
        try {
            socket?.shutdownOutput()
        } catch (_: Exception) {
        }
    }

    // ---------------- utils ----------------

    private fun cleanerLoop() {
        while (running.get()) {
            try {
                val now = System.currentTimeMillis()
                tcpFlows.entries.removeIf { (_, b) ->
                    if (now - b.lastMs > TCP_IDLE_MS) {
                        b.close()
                        bump(-1)
                        true
                    } else false
                }
                udpFlows.entries.removeIf { (_, b) ->
                    if (now - b.lastMs > UDP_IDLE_MS) {
                        b.close()
                        bump(-1)
                        true
                    } else false
                }
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun bump(d: Int) {
        val v = activeFlows.updateAndGet { (it + d).coerceAtLeast(0) }
        onFlowCountChanged?.invoke(v)
    }

    private fun beInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun seqLt(a: Int, b: Int): Boolean = a - b < 0

    private class TcpBridge(
        val key: FlowKey,
        val clientAddr: InetAddress,
        val remotePresented: InetAddress,
        val clientPort: Int,
        val remotePort: Int,
        @Volatile var clientIsn: Int,
        val selectedIp: String,
        val serverIsn: Int = (System.nanoTime() and 0x7FFFFFFF).toInt(),
        val clientNext: AtomicInteger = AtomicInteger(0),
        val serverNext: AtomicInteger = AtomicInteger(0),
        val up: AtomicBoolean = AtomicBoolean(false),
        val closed: AtomicBoolean = AtomicBoolean(false),
        @Volatile var socket: Socket? = null,
        @Volatile var lastMs: Long = System.currentTimeMillis(),
    ) {
        fun touch() {
            lastMs = System.currentTimeMillis()
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    private class UdpBridge(
        val key: FlowKey,
        val channel: DatagramChannel,
        val client: InetAddress,
        val remote: InetAddress,
        val clientPort: Int,
        val remotePort: Int,
        @Volatile var lastMs: Long = System.currentTimeMillis(),
    ) {
        fun touch() {
            lastMs = System.currentTimeMillis()
        }

        fun close() {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }
}

/** Build IPv4+TCP packet with optional MSS on SYN-ACK. */
internal fun buildIpv4Tcp(
    src: ByteArray,
    dst: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seq: Int,
    ack: Int,
    flags: Int,
    payload: ByteArray,
    withMss: Boolean,
): ByteArray {
    val optLen = if (withMss) 4 else 0
    val tcpHdr = 20 + optLen
    val tcpLen = tcpHdr + payload.size
    val total = 20 + tcpLen
    val arr = ByteArray(total)
    val bb = ByteBuffer.wrap(arr)
    // IP
    bb.put(0x45.toByte())
    bb.put(0)
    bb.putShort(total.toShort())
    bb.putShort((System.nanoTime() and 0xFFFF).toInt().toShort())
    bb.putShort(0x4000.toShort())
    bb.put(64.toByte())
    bb.put(6.toByte())
    bb.putShort(0)
    bb.put(src)
    bb.put(dst)
    // TCP
    bb.putShort(srcPort.toShort())
    bb.putShort(dstPort.toShort())
    bb.putInt(seq)
    bb.putInt(ack)
    val dataOff = (tcpHdr / 4) shl 4
    bb.put(dataOff.toByte())
    bb.put(flags.toByte())
    bb.putShort(65535.toShort())
    bb.putShort(0) // checksum
    bb.putShort(0) // urgent
    if (withMss) {
        // kind=2 len=4 MSS=1360
        bb.put(2)
        bb.put(4)
        bb.putShort(1360.toShort())
    }
    bb.put(payload)

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

    // TCP checksum (pseudo-header)
    sum = 0L
    fun addBytes(b: ByteArray) {
        var j = 0
        while (j + 1 < b.size) {
            sum += ((b[j].toInt() and 0xFF) shl 8) or (b[j + 1].toInt() and 0xFF)
            j += 2
        }
        if (j < b.size) sum += (b[j].toInt() and 0xFF) shl 8
    }
    addBytes(src)
    addBytes(dst)
    sum += 6
    sum += tcpLen
    i = 20
    while (i + 1 < total) {
        if (i == 20 + 16) { // skip checksum field
            i += 2
            continue
        }
        sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
        i += 2
    }
    if (i < total) sum += (arr[i].toInt() and 0xFF) shl 8
    while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    val tcs = (sum.inv() and 0xFFFF).toInt()
    arr[20 + 16] = (tcs ushr 8).toByte()
    arr[20 + 17] = (tcs and 0xFF).toByte()
    return arr
}

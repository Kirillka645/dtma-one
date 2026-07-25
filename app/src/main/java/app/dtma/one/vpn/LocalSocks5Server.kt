package app.dtma.one.vpn

import android.net.VpnService
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal local SOCKS5 (no auth) for hev-socks5-tunnel.
 * All outbound sockets are protected via [VpnService.protect] to avoid TUN loops.
 *
 * Supports:
 * - TCP CONNECT
 * - UDP ASSOCIATE (for DNS / QUIC / Telegram UDP)
 */
class LocalSocks5Server(
    private val vpn: VpnService,
    private val bindHost: String = "127.0.0.1",
    private val bindPort: Int = 18080,
) {
    companion object {
        private const val TAG = "DtmaSocks5"
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val udpRelays = ConcurrentHashMap<Int, UdpRelay>()

    @Volatile
    var listenPort: Int = bindPort
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindHost, bindPort))
        listenPort = ss.localPort
        server = ss
        thread(name = "dtma-socks5-accept", isDaemon = true) {
            Log.i(TAG, "SOCKS5 listening on $bindHost:$listenPort")
            while (running.get()) {
                try {
                    val client = ss.accept()
                    pool.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (running.get()) break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        udpRelays.values.forEach { it.close() }
        udpRelays.clear()
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // greeting
            if (input.read() != 0x05) return
            val nMethods = input.read()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            readFully(input, methods)
            // no-auth only
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // request
            val ver = input.read()
            val cmd = input.read()
            input.read() // rsv
            val atyp = input.read()
            if (ver != 0x05) return

            val dest = readAddress(input, atyp) ?: return
            val portHi = input.read()
            val portLo = input.read()
            if (portHi < 0 || portLo < 0) return
            val port = (portHi shl 8) or portLo

            when (cmd) {
                0x01 -> doConnect(client, input, output, dest, port)
                0x03 -> doUdpAssociate(client, input, output)
                else -> {
                    reply(output, 0x07, InetAddress.getByName("0.0.0.0"), 0)
                    client.close()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "client end: ${e.message}")
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun doConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        dest: InetAddress,
        port: Int,
    ) {
        val remote = Socket()
        try {
            if (!vpn.protect(remote)) {
                Log.w(TAG, "protect failed for TCP $dest:$port")
                reply(output, 0x01, InetAddress.getByName("0.0.0.0"), 0)
                client.close()
                return
            }
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(dest, port), 15_000)
            val local = remote.localSocketAddress as? InetSocketAddress
            val bindAddr = local?.address ?: InetAddress.getByName("0.0.0.0")
            val bindPort = local?.port ?: 0
            reply(output, 0x00, bindAddr, bindPort)

            // bidirectional pipe
            val t1 = thread(isDaemon = true, name = "s5-up") {
                pipe(input, remote.getOutputStream())
            }
            pipe(remote.getInputStream(), output)
            t1.join(100)
        } catch (e: Exception) {
            Log.d(TAG, "CONNECT $dest:$port failed: ${e.message}")
            try {
                reply(output, 0x05, InetAddress.getByName("0.0.0.0"), 0)
            } catch (_: Exception) {
            }
        } finally {
            try {
                remote.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun doUdpAssociate(client: Socket, input: InputStream, output: OutputStream) {
        val udp = DatagramSocket()
        if (!vpn.protect(udp)) {
            Log.w(TAG, "protect failed for UDP associate")
            reply(output, 0x01, InetAddress.getByName("0.0.0.0"), 0)
            udp.close()
            client.close()
            return
        }
        udp.soTimeout = 300_000
        val port = udp.localPort
        val relay = UdpRelay(udp)
        udpRelays[port] = relay
        reply(output, 0x00, InetAddress.getByName("127.0.0.1"), port)
        Log.d(TAG, "UDP ASSOCIATE on $port")

        // Keep TCP control connection open; when it closes, stop relay.
        pool.execute {
            try {
                relay.loop()
            } finally {
                udpRelays.remove(port)
                relay.close()
            }
        }
        try {
            // Wait until client closes control connection
            val buf = ByteArray(1)
            while (running.get()) {
                val n = try {
                    client.getInputStream().read(buf)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (n < 0) break
            }
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            relay.close()
            udpRelays.remove(port)
        }
    }

    private inner class UdpRelay(private val sock: DatagramSocket) {
        private val closed = AtomicBoolean(false)
        // client endpoint (first packet source)
        @Volatile
        private var clientEp: InetSocketAddress? = null
        // map remote key -> last used
        private val remotes = ConcurrentHashMap<String, InetSocketAddress>()

        fun loop() {
            val buf = ByteArray(65535)
            while (!closed.get() && running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val from = InetSocketAddress(packet.address, packet.port)
                    val data = packet.data
                    val off = packet.offset
                    val len = packet.length

                    val client = clientEp
                    if (client == null || (from.address == client.address && from.port == client.port) ||
                        (clientEp == null)
                    ) {
                        // from SOCKS5 client: parse header and forward
                        if (clientEp == null) clientEp = from
                        if (len < 4) continue
                        // RSV RSV FRAG ATYP ...
                        if (data[off].toInt() != 0 || data[off + 1].toInt() != 0) continue
                        if (data[off + 2].toInt() and 0xFF != 0) continue // no frag
                        val atyp = data[off + 3].toInt() and 0xFF
                        var p = off + 4
                        val destAddr: InetAddress
                        when (atyp) {
                            0x01 -> {
                                if (p + 4 > off + len) continue
                                destAddr = InetAddress.getByAddress(data.copyOfRange(p, p + 4))
                                p += 4
                            }
                            0x04 -> {
                                if (p + 16 > off + len) continue
                                destAddr = InetAddress.getByAddress(data.copyOfRange(p, p + 16))
                                p += 16
                            }
                            0x03 -> {
                                if (p >= off + len) continue
                                val dlen = data[p].toInt() and 0xFF
                                p++
                                if (p + dlen > off + len) continue
                                val host = String(data, p, dlen, Charsets.US_ASCII)
                                p += dlen
                                destAddr = InetAddress.getByName(host)
                            }
                            else -> continue
                        }
                        if (p + 2 > off + len) continue
                        val dport = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                        p += 2
                        val payload = data.copyOfRange(p, off + len)
                        val remote = InetSocketAddress(destAddr, dport)
                        remotes["${destAddr.hostAddress}:$dport"] = remote
                        sock.send(DatagramPacket(payload, payload.size, remote))
                    } else {
                        // from remote internet: wrap and send to client
                        val c = clientEp ?: continue
                        val header = buildUdpHeader(from.address, from.port)
                        val out = header + data.copyOfRange(off, off + len)
                        sock.send(DatagramPacket(out, out.size, c))
                    }
                } catch (_: SocketTimeoutException) {
                    // keep
                } catch (e: Exception) {
                    if (!closed.get()) Log.d(TAG, "udp relay: ${e.message}")
                    break
                }
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildUdpHeader(addr: InetAddress, port: Int): ByteArray {
        val raw = addr.address
        return if (raw.size == 4) {
            byteArrayOf(
                0, 0, 0, 0x01,
                raw[0], raw[1], raw[2], raw[3],
                (port ushr 8).toByte(), (port and 0xFF).toByte(),
            )
        } else {
            byteArrayOf(0, 0, 0, 0x04) + raw +
                byteArrayOf((port ushr 8).toByte(), (port and 0xFF).toByte())
        }
    }

    private fun reply(out: OutputStream, rep: Int, addr: InetAddress, port: Int) {
        val raw = addr.address
        val atyp: Byte
        val addrBytes: ByteArray
        if (raw.size == 4) {
            atyp = 0x01
            addrBytes = raw
        } else {
            atyp = 0x04
            addrBytes = if (raw.size == 16) raw else ByteArray(16)
        }
        val resp = ByteArray(4 + addrBytes.size + 2)
        resp[0] = 0x05
        resp[1] = rep.toByte()
        resp[2] = 0x00
        resp[3] = atyp
        System.arraycopy(addrBytes, 0, resp, 4, addrBytes.size)
        val o = 4 + addrBytes.size
        resp[o] = (port ushr 8).toByte()
        resp[o + 1] = (port and 0xFF).toByte()
        out.write(resp)
        out.flush()
    }

    private fun readAddress(input: InputStream, atyp: Int): InetAddress? {
        return try {
            when (atyp) {
                0x01 -> {
                    val b = ByteArray(4)
                    readFully(input, b)
                    InetAddress.getByAddress(b)
                }
                0x04 -> {
                    val b = ByteArray(16)
                    readFully(input, b)
                    InetAddress.getByAddress(b)
                }
                0x03 -> {
                    val len = input.read()
                    if (len <= 0) return null
                    val b = ByteArray(len)
                    readFully(input, b)
                    InetAddress.getByName(String(b, Charsets.US_ASCII))
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("EOF")
            off += n
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
        try {
            output.close()
        } catch (_: Exception) {
        }
    }
}

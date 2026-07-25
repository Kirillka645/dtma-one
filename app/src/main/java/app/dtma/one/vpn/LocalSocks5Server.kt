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
 * Local SOCKS5 (no-auth) for hev-socks5-tunnel.
 * Outbound sockets use [VpnService.protect].
 *
 * Important for Telegram:
 * - long-lived TCP must use soTimeout=0 after handshake (no 60s idle kill)
 * - UDP ASSOCIATE for MTProto/calls/DNS
 */
class LocalSocks5Server(
    private val vpn: VpnService,
    private val bindHost: String = "127.0.0.1",
    private val bindPort: Int = 18080,
    /** For Telegram DC IPs: try cellular/other network before default (no SOCKS5 needed). */
    private val telegramMultipath: Boolean = true,
    /**
     * Race ports 443/80/5222 (+ cache) on Telegram IPs so blackholed 443 does not hang the client.
     * Same host only — never swaps DC address.
     */
    private val telegramSmartPath: Boolean = true,
) {
    companion object {
        private const val TAG = "DtmaSocks5"
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    @Volatile
    var listenPort: Int = bindPort
        private set

    @Volatile
    var tcpConnectOk: Long = 0
        private set

    @Volatile
    var tcpConnectFail: Long = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindHost, bindPort))
        listenPort = ss.localPort
        server = ss
        thread(name = "dtma-socks5-accept", isDaemon = true) {
            Log.i(TAG, "SOCKS5 on $bindHost:$listenPort")
            while (running.get()) {
                try {
                    val client = ss.accept()
                    // Only for greeting phase; cleared after CONNECT setup.
                    client.soTimeout = 30_000
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
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (input.read() != 0x05) return
            val nMethods = input.read()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            readFully(input, methods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val ver = input.read()
            val cmd = input.read()
            input.read() // rsv
            val atyp = input.read()
            if (ver != 0x05) return

            val dest = readAddress(input, atyp) ?: run {
                reply(output, 0x08, InetAddress.getByName("0.0.0.0"), 0)
                return
            }
            val portHi = input.read()
            val portLo = input.read()
            if (portHi < 0 || portLo < 0) return
            val port = (portHi shl 8) or portLo

            when (cmd) {
                0x01 -> doConnect(client, input, output, dest, port)
                0x03 -> doUdpAssociate(client, output)
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
        var remote: Socket? = null
        try {
            client.soTimeout = 0
            val isTg = TelegramRanges.isTelegramHost(dest)
            if (isTg && telegramSmartPath) {
                val smart = TelegramSmartConnect.connect(
                    context = vpn,
                    vpn = vpn,
                    dest = dest,
                    requestedPort = port,
                    multipath = telegramMultipath,
                )
                if (smart != null) {
                    remote = smart.socket
                    Log.i(
                        TAG,
                        "Telegram smart ${dest.hostAddress}:$port → :${smart.connectedPort} via ${smart.via}" +
                            if (smart.remappedPort) " (port remap)" else "",
                    )
                }
            } else if (isTg && telegramMultipath) {
                val multi = MultipathEgress.connect(
                    context = vpn,
                    vpn = vpn,
                    dest = dest,
                    port = port,
                    timeoutMs = 8_000,
                    preferAlternateFirst = true,
                )
                if (multi != null) {
                    remote = multi.socket
                    Log.i(TAG, "Telegram multipath ${dest.hostAddress}:$port via ${multi.via}")
                }
            }
            if (remote == null) {
                // Non-Telegram, or smart race lost: single attempt with short timeout for TG.
                val sock = Socket()
                remote = sock
                if (!vpn.protect(sock)) {
                    Log.w(TAG, "protect failed TCP ${dest.hostAddress}:$port")
                    reply(output, 0x01, InetAddress.getByName("0.0.0.0"), 0)
                    tcpConnectFail++
                    client.close()
                    return
                }
                sock.tcpNoDelay = true
                sock.keepAlive = true
                // Telegram push sessions stay idle for a long time — never time out the pipe.
                sock.soTimeout = 0
                val connectTimeout = if (isTg) 4_000 else 15_000
                sock.connect(InetSocketAddress(dest, port), connectTimeout)
                if (isTg) {
                    TelegramPathCache.rememberOk(dest.hostAddress ?: "", port, "fallback")
                }
            }

            val r = remote!!
            val local = r.localSocketAddress as? InetSocketAddress
            val bindAddr = local?.address ?: InetAddress.getByName("0.0.0.0")
            val bindPort = local?.port ?: 0
            reply(output, 0x00, bindAddr, bindPort)
            tcpConnectOk++
            Log.d(TAG, "CONNECT ok ${dest.hostAddress}:$port")

            val up = thread(isDaemon = true, name = "s5-c2s") {
                pipe(input, r.getOutputStream())
                try {
                    r.shutdownOutput()
                } catch (_: Exception) {
                }
            }
            pipe(r.getInputStream(), output)
            try {
                client.shutdownOutput()
            } catch (_: Exception) {
            }
            up.join(500)
        } catch (e: Exception) {
            tcpConnectFail++
            Log.d(TAG, "CONNECT fail ${dest.hostAddress}:$port — ${e.message}")
            try {
                reply(output, 0x05, InetAddress.getByName("0.0.0.0"), 0)
            } catch (_: Exception) {
            }
        } finally {
            try {
                remote?.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun doUdpAssociate(client: Socket, output: OutputStream) {
        val relaySock = DatagramSocket()
        if (!vpn.protect(relaySock)) {
            Log.w(TAG, "protect failed UDP ASSOCIATE")
            reply(output, 0x01, InetAddress.getByName("0.0.0.0"), 0)
            relaySock.close()
            client.close()
            return
        }
        relaySock.soTimeout = 0
        val port = relaySock.localPort
        reply(output, 0x00, InetAddress.getByName("127.0.0.1"), port)
        Log.d(TAG, "UDP ASSOCIATE port=$port")

        // Long-lived control connection for Telegram / DNS / QUIC
        client.soTimeout = 0
        val closed = AtomicBoolean(false)
        val clientEp = arrayOfNulls<InetSocketAddress>(1)

        val relayThread = thread(isDaemon = true, name = "s5-udp-$port") {
            val buf = ByteArray(65535)
            while (!closed.get() && running.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    relaySock.receive(pkt)
                    val from = InetSocketAddress(pkt.address, pkt.port)
                    val data = pkt.data
                    val off = pkt.offset
                    val len = pkt.length
                    val ep = clientEp[0]
                    if (ep == null || (from.address == ep.address && from.port == ep.port)) {
                        if (clientEp[0] == null) clientEp[0] = from
                        if (len < 4) continue
                        if (data[off].toInt() != 0 || data[off + 1].toInt() != 0) continue
                        if ((data[off + 2].toInt() and 0xFF) != 0) continue
                        val atyp = data[off + 3].toInt() and 0xFF
                        var p = off + 4
                        val destAddr: InetAddress = when (atyp) {
                            0x01 -> {
                                if (p + 4 > off + len) continue
                                val a = InetAddress.getByAddress(data.copyOfRange(p, p + 4))
                                p += 4
                                a
                            }
                            0x04 -> {
                                if (p + 16 > off + len) continue
                                val a = InetAddress.getByAddress(data.copyOfRange(p, p + 16))
                                p += 16
                                a
                            }
                            0x03 -> {
                                if (p >= off + len) continue
                                val dlen = data[p].toInt() and 0xFF
                                p++
                                if (p + dlen > off + len) continue
                                val host = String(data, p, dlen, Charsets.US_ASCII)
                                p += dlen
                                InetAddress.getByName(host)
                            }
                            else -> continue
                        }
                        if (p + 2 > off + len) continue
                        val dport = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                        p += 2
                        val payload = data.copyOfRange(p, off + len)
                        relaySock.send(DatagramPacket(payload, payload.size, destAddr, dport))
                    } else {
                        val c = clientEp[0] ?: continue
                        val header = buildUdpHeader(from.address, from.port)
                        val outBytes = header + data.copyOfRange(off, off + len)
                        relaySock.send(DatagramPacket(outBytes, outBytes.size, c))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (!closed.get()) Log.d(TAG, "udp relay end: ${e.message}")
                    break
                }
            }
            try {
                relaySock.close()
            } catch (_: Exception) {
            }
        }

        try {
            val sink = ByteArray(256)
            while (running.get()) {
                val n = client.getInputStream().read(sink)
                if (n < 0) break
            }
        } finally {
            closed.set(true)
            try {
                relaySock.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
            relayThread.join(300)
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
        } else if (raw.size == 16) {
            atyp = 0x04
            addrBytes = raw
        } else {
            atyp = 0x01
            addrBytes = byteArrayOf(0, 0, 0, 0)
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
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
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

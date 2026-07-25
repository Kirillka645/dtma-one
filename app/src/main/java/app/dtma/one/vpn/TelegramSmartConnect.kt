package app.dtma.one.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * For Telegram DC IPs only:
 * - Race TCP connect across ports (443 / 80 / 5222) and underlying networks (Wi‑Fi / LTE)
 * - Prefer ports that [TelegramPathCache] already saw working (from probe)
 * - May land on a *different port* than the app requested (same host) — MTProto is fine with that
 * - Does **not** rewrite to another DC IP
 *
 * Goal: if probe is 2/26, use those 2 instead of hanging 15s on blackholed 443.
 */
object TelegramSmartConnect {
    private const val TAG = "DtmaTgSmart"
    private val racePool = Executors.newCachedThreadPool { r ->
        Thread(r, "tg-smart-race").apply { isDaemon = true }
    }

    data class Result(
        val socket: Socket,
        val connectedPort: Int,
        val via: String,
        val remappedPort: Boolean,
    )

    fun connect(
        context: Context,
        vpn: VpnService,
        dest: InetAddress,
        requestedPort: Int,
        multipath: Boolean,
        overallTimeoutMs: Long = 4_500,
        perAttemptTimeoutMs: Int = 2_800,
    ): Result? {
        val host = dest.hostAddress ?: return null
        val ports = TelegramPathCache.preferredPorts(host, requestedPort)
        val networks: List<Network?> = if (multipath) {
            listCandidateNetworks(context) + listOf(null) // null = default after protect
        } else {
            listOf(null)
        }.distinct()

        // Cap parallelism: ports × nets (typically 4×2)
        data class Job(val network: Network?, val port: Int, val label: String)

        val jobs = ArrayList<Job>()
        for (p in ports) {
            for (n in networks) {
                val label = when {
                    n == null -> "default"
                    else -> networkLabel(context, n)
                }
                // Prefer: known-good ports + non-default nets first (already ordered by ports)
                jobs.add(Job(n, p, label))
            }
        }
        // Put cache-hot ports on alternate net first
        jobs.sortBy { job ->
            val known = TelegramPathCache.preferredPorts(host, requestedPort).indexOf(job.port)
            val netRank = if (job.network != null) 0 else 1
            known * 10 + netRank
        }

        val winner = AtomicReference<Result?>(null)
        val losers = ConcurrentLinkedQueue<Socket>()
        val latch = CountDownLatch(1)
        val t0 = System.currentTimeMillis()

        Log.i(TAG, "race $host reqPort=$requestedPort ports=$ports nets=${networks.size}")

        for (job in jobs) {
            racePool.execute {
                if (winner.get() != null) return@execute
                val sock = Socket()
                try {
                    if (!vpn.protect(sock)) {
                        sock.close()
                        return@execute
                    }
                    if (job.network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            job.network.bindSocket(sock)
                        } catch (_: Exception) {
                            // fall through with unbound protected socket
                        }
                    }
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.soTimeout = 0
                    sock.connect(InetSocketAddress(dest, job.port), perAttemptTimeoutMs)
                    val rtt = System.currentTimeMillis() - t0
                    val result = Result(
                        socket = sock,
                        connectedPort = job.port,
                        via = job.label,
                        remappedPort = job.port != requestedPort,
                    )
                    if (winner.compareAndSet(null, result)) {
                        TelegramPathCache.rememberOk(host, job.port, job.label, rtt)
                        Log.i(
                            TAG,
                            "WIN $host:${job.port} (req $requestedPort) via ${job.label} ${rtt}ms" +
                                if (result.remappedPort) " [port remap]" else "",
                        )
                        latch.countDown()
                    } else {
                        losers.add(sock)
                    }
                } catch (_: Exception) {
                    TelegramPathCache.rememberFail(host, job.port)
                    try {
                        sock.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        latch.await(overallTimeoutMs, TimeUnit.MILLISECONDS)
        // Close late winners / losers
        while (true) {
            val s = losers.poll() ?: break
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        val w = winner.get()
        if (w == null) {
            Log.w(TAG, "race FAIL $host:$requestedPort after ${System.currentTimeMillis() - t0}ms")
        }
        return w
    }

    private fun listCandidateNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = ArrayList<Network>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            out.add(n)
        }
        // Cellular first (common case: Wi‑Fi censors, LTE does not)
        return out.sortedBy { n ->
            val c = cm.getNetworkCapabilities(n)
            when {
                c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 0
                c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                else -> 2
            }
        }
    }

    private fun networkLabel(context: Context, network: Network): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(network) ?: return "net"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "net"
        }
    }
}

package app.dtma.one.vpn

import android.util.Log

/**
 * JNI bridge to hev-socks5-tunnel (lwIP-based tun2socks).
 * Native registration expects these exact static method names.
 */
object HevTunnel {
    private const val TAG = "HevTunnel"

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("hev-socks5-tunnel")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "native lib missing: ${e.message}")
            false
        }
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}

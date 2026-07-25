package app.dtma.one.bypass

import android.util.Log
import com.wireguard.android.backend.Tunnel

/** Single in-app WARP tunnel instance. */
object WarpTunnel : Tunnel {
    private const val TAG = "DtmaWarp"
    const val NAME = "dtma-warp"

    @Volatile
    var lastState: Tunnel.State = Tunnel.State.DOWN
        private set

    override fun getName(): String = NAME

    override fun onStateChange(newState: Tunnel.State) {
        lastState = newState
        Log.i(TAG, "tunnel state → $newState")
    }
}

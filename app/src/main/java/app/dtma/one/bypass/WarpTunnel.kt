package app.dtma.one.bypass

import com.wireguard.android.backend.Tunnel

/** Single in-app WARP tunnel instance. */
object WarpTunnel : Tunnel {
    const val NAME = "dtma-warp"

    @Volatile
    var lastState: Tunnel.State = Tunnel.State.DOWN
        private set

    override fun getName(): String = NAME

    override fun onStateChange(newState: Tunnel.State) {
        lastState = newState
    }
}

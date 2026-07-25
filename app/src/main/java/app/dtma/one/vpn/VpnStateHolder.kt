package app.dtma.one.vpn

import app.dtma.one.core.model.NetworkContext
import app.dtma.one.core.model.VpnUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnRuntimeStatus(
    val state: VpnUiState = VpnUiState.OFF,
    val message: String = "",
    val flowCount: Int = 0,
    val networkContext: NetworkContext? = null,
    val ipv4: Boolean = false,
    val ipv6: Boolean = false,
    val limitedMode: Boolean = false,
)

object VpnStateHolder {
    private val _status = MutableStateFlow(VpnRuntimeStatus())
    val status: StateFlow<VpnRuntimeStatus> = _status.asStateFlow()

    fun update(transform: (VpnRuntimeStatus) -> VpnRuntimeStatus) {
        _status.value = transform(_status.value)
    }

    fun set(status: VpnRuntimeStatus) {
        _status.value = status
    }
}

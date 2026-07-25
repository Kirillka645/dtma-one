package app.dtma.one.bypass

/**
 * Live WARP UI state — never rely on a single boolean "running".
 */
enum class WarpMode {
    /** Tunnel not active. */
    OFF,
    /** Registering / bringing tunnel up / handshake. */
    STARTING,
    /** Handshake ok and recent peer traffic. */
    ON,
    /** Was ON but peer went silent (dead tunnel). */
    UNHEALTHY,
    /** Last start failed. */
    ERROR,
}

data class WarpLiveStatus(
    val mode: WarpMode = WarpMode.OFF,
    val message: String = "WARP выключен",
    val endpoint: String? = null,
    val address: String? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null,
    /** How many generate+start attempts in the last start() call. */
    val startAttempts: Int = 0,
    val autoRegenUsed: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val isOn: Boolean get() = mode == WarpMode.ON
    val isBusy: Boolean get() = mode == WarpMode.STARTING

    fun modeLabelRu(): String = when (mode) {
        WarpMode.OFF -> "ВЫКЛ"
        WarpMode.STARTING -> "ЗАПУСК…"
        WarpMode.ON -> "ВКЛ"
        WarpMode.UNHEALTHY -> "БОЛЬНОЙ"
        WarpMode.ERROR -> "ОШИБКА"
    }

    fun trafficLine(): String {
        if (mode == WarpMode.OFF && rxBytes == 0L && txBytes == 0L) return "↓ —  ↑ —"
        return "↓ ${formatBytes(rxBytes)}  ↑ ${formatBytes(txBytes)}"
    }

    companion object {
        fun formatBytes(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
            n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
            else -> "%.2f GB".format(n / (1024.0 * 1024 * 1024))
        }
    }
}

package app.dtma.one.update

/**
 * Result of checking GitHub Releases for a newer app version.
 */
data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    val releaseUrl: String,
    val notes: String,
    /** Direct APK asset URL from GitHub Releases (for in-app install). */
    val apkUrl: String? = null,
    val apkName: String? = null,
    val apkSizeBytes: Long = 0L,
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data object UpToDate : UpdateCheckState()
    data class Available(val update: AvailableUpdate) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

/** In-app APK download / install (like KupuProxy-style self-update). */
sealed class ApkInstallState {
    data object Idle : ApkInstallState()
    data class Downloading(val progress: Float, val received: Long, val total: Long) : ApkInstallState()
    data class Ready(val path: String) : ApkInstallState()
    data object Installing : ApkInstallState()
    data class Error(val message: String) : ApkInstallState()
}

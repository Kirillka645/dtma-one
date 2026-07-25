package app.dtma.one.update

/**
 * Result of checking GitHub Releases for a newer app version.
 */
data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    val releaseUrl: String,
    val notes: String,
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data object UpToDate : UpdateCheckState()
    data class Available(val update: AvailableUpdate) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

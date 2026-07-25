package app.dtma.one.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dtma.one.BuildConfig
import app.dtma.one.DtmaApp
import app.dtma.one.MainActivity
import app.dtma.one.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Runs update checks, in-app state, notification; install via [ApkUpdater].
 */
object UpdateNotifier {
    private const val TAG = "DtmaUpdate"
    private const val NOTIFICATION_ID = 1001
    /** Avoid hammering GitHub: automatic check at most every 12h. */
    const val MIN_AUTO_INTERVAL_MS = 12L * 60L * 60L * 1000L

    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    private val checker = UpdateChecker()

    /**
     * @param force ignore throttle and re-check even if recently checked
     */
    suspend fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val repo = DtmaApp.instance.settingsRepository
        val settings = repo.settings.first()
        if (!settings.updateCheckEnabled) {
            _state.value = UpdateCheckState.Idle
            return
        }

        val now = System.currentTimeMillis()
        if (!force && settings.lastUpdateCheckMs > 0 &&
            now - settings.lastUpdateCheckMs < MIN_AUTO_INTERVAL_MS
        ) {
            val cached = settings.lastKnownUpdateTag
            if (cached.isNotBlank() &&
                cached != settings.dismissedUpdateTag &&
                VersionCompare.isNewer(cached, BuildConfig.VERSION_NAME)
            ) {
                _state.value = UpdateCheckState.Available(
                    AvailableUpdate(
                        tag = cached,
                        versionName = VersionCompare.normalize(cached),
                        releaseUrl = settings.lastKnownUpdateUrl.ifBlank {
                            "https://github.com/Kirillka645/dtma-one/releases"
                        },
                        notes = "",
                        apkUrl = settings.lastKnownApkUrl.ifBlank { null },
                        apkName = settings.lastKnownApkName.ifBlank { null },
                    ),
                )
            }
            return
        }

        _state.value = UpdateCheckState.Checking
        val result = checker.check()
        repo.markUpdateChecked(now)

        result.fold(
            onSuccess = { update ->
                if (update == null) {
                    repo.clearKnownUpdate()
                    _state.value = UpdateCheckState.UpToDate
                    Log.i(TAG, "up to date")
                } else {
                    repo.setKnownUpdate(
                        tag = update.tag,
                        url = update.releaseUrl,
                        apkUrl = update.apkUrl.orEmpty(),
                        apkName = update.apkName.orEmpty(),
                    )
                    _state.value = UpdateCheckState.Available(update)
                    Log.i(TAG, "update available: ${update.tag} apk=${update.apkUrl}")
                    if (update.tag != settings.dismissedUpdateTag) {
                        showNotification(app, update)
                    }
                }
            },
            onFailure = { e ->
                _state.value = UpdateCheckState.Error(e.message ?: "check failed")
            },
        )
    }

    suspend fun dismiss(context: Context) {
        val current = _state.value
        if (current is UpdateCheckState.Available) {
            DtmaApp.instance.settingsRepository.dismissUpdate(current.update.tag)
        }
        _state.value = UpdateCheckState.Idle
        ApkUpdater.reset()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    suspend fun downloadAndInstall(context: Context) {
        val current = _state.value
        if (current !is UpdateCheckState.Available) {
            ApkUpdater.reset()
            return
        }
        ApkUpdater.downloadAndInstall(context, current.update)
    }

    private fun showNotification(context: Context, update: AvailableUpdate) {
        try {
            val openApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }
            val appPi = PendingIntent.getActivity(
                context,
                1,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = context.getString(
                R.string.update_notification_text,
                update.versionName,
            )
            val notification = NotificationCompat.Builder(context, DtmaApp.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(appPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(
                    0,
                    context.getString(R.string.update_install_in_app),
                    appPi,
                )
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    const val EXTRA_OPEN_UPDATE = "app.dtma.one.OPEN_UPDATE"
}

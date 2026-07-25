package app.dtma.one

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.dtma.one.core.storage.RvecStore
import app.dtma.one.core.storage.SettingsRepository
import app.dtma.one.update.UpdateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DtmaApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var rvecStore: RvecStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        rvecStore = RvecStore(this)
        createNotificationChannels()
        // Background update check (throttled); posts notification if newer release exists.
        appScope.launch {
            UpdateNotifier.check(this@DtmaApp, force = false)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATES,
                    getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = getString(R.string.update_notification_channel_desc)
                },
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "dtma_vpn"
        const val CHANNEL_UPDATES = "dtma_updates"
        lateinit var instance: DtmaApp
            private set
    }
}

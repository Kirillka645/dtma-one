package app.dtma.one

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.dtma.one.core.storage.RvecStore
import app.dtma.one.core.storage.SettingsRepository

class DtmaApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var rvecStore: RvecStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        rvecStore = RvecStore(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "dtma_vpn"
        lateinit var instance: DtmaApp
            private set
    }
}

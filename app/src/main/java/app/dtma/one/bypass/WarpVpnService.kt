package app.dtma.one.bypass

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import app.dtma.one.DtmaApp
import app.dtma.one.MainActivity
import app.dtma.one.R
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VpnService host required by wireguard-android [GoBackend].
 * Establishes the real TUN for free Cloudflare WARP inside DTMA.
 */
class WarpVpnService : GoBackend.VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    WarpController.stop(this@WarpVpnService)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isAlive = false
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onRevoke() {
        scope.launch {
            WarpController.stop(this@WarpVpnService)
        }
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WarpVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DtmaApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.warp_notification_title))
            .setContentText(getString(R.string.warp_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vpn_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "WarpVpnService"
        private const val NOTIFICATION_ID = 43
        const val ACTION_START = "app.dtma.one.warp.START"
        const val ACTION_STOP = "app.dtma.one.warp.STOP"

        @Volatile
        var isAlive: Boolean = false
            internal set
    }
}

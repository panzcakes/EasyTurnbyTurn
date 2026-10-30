package app.clusternav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Keeps the process alive in the background (foreground service with a notification) so the
 * Bluetooth link survives the screen being locked or another app, such as the maps app, being
 * in front. The link itself is owned by [Cluster].
 */
class ClusterService : Service() {

    companion object {
        const val ACTION_STOP = "app.clusternav.STOP"
        private const val CHANNEL_ID = "cluster_link"
        private const val NOTIFICATION_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var notReadySince = 0L

    /** Stops the service (and its notification) if the bike has not been connected for too long. */
    private val idleCheck = object : Runnable {
        override fun run() {
            Relay.init(this@ClusterService)
            val limit = Relay.idleStopMinutes
            if (limit > 0) {
                val now = SystemClock.elapsedRealtime()
                if (Cluster.state == ClusterLink.State.READY) {
                    notReadySince = now
                } else if (now - notReadySince >= limit * 60_000L) {
                    Cluster.log("Bike not connected for $limit minutes - stopping to save battery")
                    // During a trip the relay would just restart the service, so switch it off too.
                    if (Relay.tripActive) Relay.enabled = false
                    Cluster.disconnect()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            }
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Relay.init(this)
            Relay.enabled = false // "Stop" means everything, including the Google Maps relay
            Cluster.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        notReadySince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 15_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        // If the system or the user stops the service, drop the link too.
        Cluster.disconnect()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cluster link", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ClusterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Easy Turn by Turn is running")
            .setContentText("Start navigation in Google Maps - the cluster connects by itself")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }
}

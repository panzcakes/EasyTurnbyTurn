package app.clusternav

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives notifications once the user has granted "notification access". The system keeps this
 * service bound while access is granted (and restarts it if the app was killed), so the relay
 * keeps working with the app closed.
 */
class MapsListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Relay.init(this)
        Cluster.init(this)
        Cluster.log("Easy Turn by Turn v${Cluster.VERSION}: notification access active, relay ${if (Relay.enabled) "ON" else "off"}")
        if (Relay.enabled) {
            try {
                startForegroundService(Intent(this, ClusterService::class.java))
            } catch (e: Exception) {
                Cluster.log("Could not start the background service: ${e.javaClass.simpleName}")
            }
            // a navigation may already be running (app was restarted mid-trip)
            try {
                activeNotifications?.forEach { Relay.onPosted(this, it) }
            } catch (_: Exception) {
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Relay.init(this)
        Relay.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Relay.init(this)
        Relay.onRemoved(sbn)
    }
}

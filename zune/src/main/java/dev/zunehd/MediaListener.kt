package dev.zunehd

import android.service.notification.NotificationListenerService

/**
 * Does nothing with notifications. Android only hands out other apps' media sessions
 * (Tidal's play/pause, track info, queue) to an app that holds notification access.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Player.init(this)
        Player.connect()
    }
}

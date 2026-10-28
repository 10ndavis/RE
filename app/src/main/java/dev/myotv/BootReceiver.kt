package dev.myotv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the service after a reboot if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            MyoService.settings(context).getBoolean(MyoService.PREF_AUTOSTART, false)
        ) {
            MyoService.start(context)
        }
    }
}

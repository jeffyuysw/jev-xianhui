package com.jev.priority.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the app back after a reboot — and after an update replaces it.
 *
 * Before this existed, a phone that rebooted overnight simply stopped working
 * until somebody opened 先回 by hand: the keep-alive service was only ever
 * started from MainActivity.onResume(), so nothing else could revive it.
 *
 * Note what this deliberately does *not* do: it never starts a service. We
 * cannot start the notification listener ourselves (the system owns that
 * binding) and Android 12+ blocks startForegroundService from a broadcast.
 * So it only asks the system to rebind the listener; once that bind lands,
 * MsgNotificationListener.onListenerConnected() starts the keep-alive from a
 * context where it is allowed to.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            // Deliberately NOT LOCKED_BOOT_COMPLETED: it fires before the user
            // unlocks the phone (Direct Boot), where the app's private storage
            // is not readable and anything touching Prefs would break. The
            // normal BOOT_COMPLETED arrives after unlock, which is what we want.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MsgNotificationListener.rebind(context)
                Log.i(TAG, "rebind requested after ${intent.action}")
            }
        }
    }

    companion object {
        private const val TAG = "JEVPRIORITY"
    }
}

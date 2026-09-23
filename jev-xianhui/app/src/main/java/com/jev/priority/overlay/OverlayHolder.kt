package com.jev.priority.overlay

import android.content.Context

/**
 * The one and only overlay instance, shared by the settings screen and the
 * keep-alive service.
 *
 * Two reasons it is a singleton rather than a field on MainActivity:
 *
 *  1. **Restoring after a reboot.** The overlay is drawn by us, so the system
 *     never brings it back: only the notification listener is rebound on boot.
 *     Without a shared instance the keep-alive service could not put the bubble
 *     back on screen, and a rebooted phone would judge messages happily while
 *     showing the user nothing at all until they opened the app by hand.
 *
 *  2. **Context lifetime.** The overlay outlives the activity that created it
 *     (it is a SYSTEM_ALERT_WINDOW, deliberately independent of it). Holding an
 *     Activity here would pin the whole activity in memory for as long as the
 *     bubble is on screen, so the application context is used instead.
 */
object OverlayHolder {

    @Volatile
    private var instance: PriorityOverlay? = null

    fun get(context: Context): PriorityOverlay {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: PriorityOverlay(context.applicationContext).also { instance = it }
        }
    }
}

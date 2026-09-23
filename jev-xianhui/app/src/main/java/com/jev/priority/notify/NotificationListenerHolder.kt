package com.jev.priority.notify

/**
 * A one-slot bridge so the overlay can ask the notification listener to
 * dismiss a row's original notification.
 *
 * The listener is bound by the system and the overlay lives in the app process,
 * so a static reference is enough here — there is only ever one listener
 * instance. Keeping it in its own file avoids the overlay depending on the
 * listener class directly.
 */
object NotificationListenerHolder {

    @Volatile
    var instance: MsgNotificationListener? = null
}

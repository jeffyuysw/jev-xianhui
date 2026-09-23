package com.jev.priority.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jev.priority.core.Prefs
import com.jev.priority.overlay.OverlayHolder

/**
 * A foreground service whose only job is to keep the process alive.
 *
 * Chinese ROMs (MIUI / HyperOS and friends) freeze background processes
 * aggressively; without a visible foreground notification the listener stops
 * receiving anything after a few minutes. Users still have to add this app to
 * the ROM's autostart and battery-unrestricted lists — see README.
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * ROMs unbind the notification listener without ever telling us — the
     * process is simply torn down, so onListenerDisconnected() never runs and
     * its requestRebind() never happens. This watchdog is the backstop: while
     * this service is alive it notices a missing listener and asks the system
     * for a new bind.
     *
     * It only asks when the listener is genuinely gone. Rebinding while it is
     * connected would needlessly tear down a working binding.
     */
    /**
     * Puts the bubble back on screen if the user had it open.
     *
     * The overlay is drawn by us, so nothing else restores it: after a reboot
     * or a ROM clean-up the listener gets rebound and judging resumes, but the
     * user sees no bubble at all — the app looks dead even though it is
     * working. [Prefs.overlayShown] records the user's last intent, so honour it
     * here. Only when the overlay permission survived, otherwise show() would
     * fail and toast at a moment the user is not even looking.
     */
    private fun restoreOverlayIfWanted() {
        val prefs = Prefs(this)
        if (!prefs.overlayShown) return
        if (!Settings.canDrawOverlays(this)) return
        OverlayHolder.get(this).show()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (NotificationListenerHolder.instance == null) {
                Log.w(TAG, "listener missing, asking for a rebind")
                MsgNotificationListener.rebind(this@KeepAliveService)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Remove first: START_STICKY re-delivers onStartCommand, and posting a
        // second watchdog each time would stack them up.
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        restoreOverlayIfWanted()
    }

    /**
     * A foreground service that was refused foreground status is guaranteed to
     * be killed, and Android throws if it is left running — so stop cleanly
     * rather than letting the process crash.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
            .isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        restoreOverlayIfWanted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        Log.w(TAG, "keepalive destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "消息监听", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("先回在运行")
            .setContentText("正在为你判断哪些消息要马上回")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "JEVPRIORITY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "jev_priority_keepalive"

        /** 30s is often enough for a ROM to have killed the bind, not us. */
        private const val CHECK_INTERVAL_MS = 30_000L
    }
}

package com.jev.priority.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
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

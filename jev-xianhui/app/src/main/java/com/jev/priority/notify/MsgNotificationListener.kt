package com.jev.priority.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.jev.priority.core.MsgItem
import com.jev.priority.core.MsgStore
import com.jev.priority.core.Prefs
import com.jev.priority.jev.PriorityClient
import kotlin.concurrent.thread

/**
 * Collects chat notifications and asks Jev to triage each one.
 *
 * This is the official NotificationListenerService API — no accessibility
 * service, no disguised class name, and it works without opening WeChat. It
 * also survives where an accessibility-based reader would not, because the
 * system keeps the binding alive.
 *
 * One conversation keeps one row: a new line replaces the previous one, which
 * matches the question "what still needs a reply" better than a raw feed would.
 */
class MsgNotificationListener : NotificationListenerService() {

    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        NotificationListenerHolder.instance = this
    }

    override fun onDestroy() {
        if (NotificationListenerHolder.instance === this) {
            NotificationListenerHolder.instance = null
        }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
        startKeepAlive()
    }

    /**
     * The system has just bound us, which is the earliest moment the process
     * is alive *without* the user having opened the app. Starting the
     * keep-alive here — and not only from MainActivity — is what makes the app
     * survive a night-time reboot or a ROM clean-up: previously the only path
     * back to life was MainActivity.onResume(), so a phone that had been
     * rebooted stayed dead and silent until someone opened 先回 by hand.
     *
     * Kept inside runCatching because a ROM is free to refuse the foreground
     * start; that must never take the listener down with it.
     */
    private fun startKeepAlive() {
        runCatching {
            ContextCompat.startForegroundService(
                this, Intent(this, KeepAliveService::class.java)
            )
        }.onFailure { Log.w(TAG, "keepalive start failed: ${it.message}") }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "notification listener disconnected")
        // Ask the system to rebind instead of staying dead.
        NotificationListenerService.requestRebind(
            ComponentName(this, MsgNotificationListener::class.java)
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val watch = prefs.watchPkgs
        if (watch.isNotEmpty() && pkg !in watch) return

        val n = sbn.notification ?: return
        // Ongoing rows (music, VPN, downloads) are status, not messages.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        // Messaging apps often put several lines in EXTRA_TEXT; prefer it.
        val raw = pickText(extras)
        if (raw.isBlank()) return

        val (who, body) = splitSender(title, raw)
        if (body.isBlank()) return

        val item = MsgItem(
            key = "$pkg|$who",
            pkg = pkg,
            appName = appLabel(pkg),
            title = who,
            text = body,
            timeMs = sbn.postTime
        )
        item.contentIntent = n.contentIntent
        item.sbnKey = sbn.key

        MsgStore.upsert(item)

        if (prefs.autoJudge && prefs.apiKey.isNotBlank()) {
            judgeAsync(item)
        }
    }

    /**
     * Legacy removal callback, kept for ROM paths that never hand over a
     * reason. With no reason to inspect it has to be treated as a real
     * dismissal, so it is only honoured in background-only mode.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (prefs.backgroundOnly) dropRow(sbn)
    }

    /**
     * The reason is what tells a dismissal apart from a retraction.
     *
     * WeChat cancels its own notification the instant it comes to the
     * foreground — that arrives as REASON_APP_CANCEL. In the default mode that
     * is not "handled": you are sitting inside WeChat, the message still wants
     * an answer, so the row has to survive. Swiping it away, tapping it, or
     * clearing the shade are real dismissals and always drop the row.
     *
     * Background-only mode keeps the old, stricter rule: anything that leaves
     * the shade takes its row with it.
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap,
        reason: Int,
    ) {
        val userHandled = reason == NotificationListenerService.REASON_CANCEL ||
            reason == NotificationListenerService.REASON_CANCEL_ALL ||
            reason == NotificationListenerService.REASON_CLICK ||
            reason == NotificationListenerService.REASON_LISTENER_CANCEL
        if (prefs.backgroundOnly || userHandled) dropRow(sbn)
    }

    /** Drops the row behind a notification when it is the one we captured. */
    private fun dropRow(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val raw = pickText(extras)
        if (raw.isBlank()) return
        val (who, body) = splitSender(title, raw)
        val key = "$pkg|$who"
        val current = MsgStore.all().firstOrNull { it.key == key }
        // Only drop it when this was the row we captured (same text), so a
        // stale cancel cannot wipe a newer message from the same conversation.
        if (current != null && current.text == body) {
            MsgStore.remove(key)
        }
    }

    /**
     * Dismiss the live notification behind a row.
     *
     * Prefers the exact StatusBarNotification key captured with the row. Falls
     * back to recomputing our own per-conversation key, since a notification
     * may have been re-posted with a new key by the time the user taps.
     */
    fun dismissByKey(sbnKey: String, conversationKey: String) {
        if (sbnKey.isNotBlank()) {
            runCatching { cancelNotification(sbnKey) }
        }
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.forEach { sbn ->
            val pkg = sbn.packageName ?: return@forEach
            val extras = sbn.notification?.extras ?: return@forEach
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val raw = pickText(extras)
            if (raw.isBlank()) return@forEach
            val (who, _) = splitSender(title, raw)
            if ("$pkg|$who" == conversationKey) {
                runCatching { cancelNotification(sbn.key) }
            }
        }
    }

    private fun pickText(extras: Bundle): String {
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (!big.isNullOrBlank()) return big.trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            val joined = lines.joinToString(" ") { it?.toString().orEmpty() }.trim()
            if (joined.isNotBlank()) return joined
        }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
    }

    /**
     * WeChat group notifications arrive as "nickname: content" or
     * "nickname：content" with the group name in the title. Split only when the
     * prefix looks like a name — short, single line, no URL — so messages that
     * merely contain a colon stay intact.
     */
    private fun splitSender(title: String, text: String): Pair<String, String> {
        val idx = text.indexOfAny(charArrayOf(':', '：'))
        if (idx > 0 && idx <= 20) {
            val head = text.substring(0, idx).trim()
            val rest = text.substring(idx + 1).trim()
            val looksLikeName = head.isNotBlank() &&
                !head.contains("\n") &&
                !head.startsWith("http", ignoreCase = true) &&
                head.none { it.isWhitespace() && it != ' ' }
            if (looksLikeName && rest.isNotBlank()) return head to rest
        }
        // One-to-one chats put the sender in the title.
        return (title.ifBlank { text.take(12) }) to text
    }

    /**
     * App labels are immutable for the lifetime of an installed package, so the
     * PackageManager round-trip is worth caching: getApplicationInfo() is a
     * cross-process call, and onNotificationPosted runs on the main thread for
     * every single notification.
     */
    private val labelCache = HashMap<String, String>()

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun judgeAsync(item: MsgItem) {
        item.judging = true
        thread(name = "jev-judge") {
            try {
                PriorityClient(prefs).judge(item)
            } catch (e: Exception) {
                Log.w(TAG, "judge failed: ${e.message}")
                item.error = e.message
            } finally {
                item.judging = false
                MsgStore.notifyChange()
            }
        }
    }

    companion object {
        private const val TAG = "JEVPRIORITY"

        /** Re-enabled from the UI after the user grants notification access. */
        fun rebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, MsgNotificationListener::class.java)
                )
            }
        }
    }
}

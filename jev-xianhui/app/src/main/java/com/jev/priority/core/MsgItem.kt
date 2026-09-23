package com.jev.priority.core

import android.app.PendingIntent

/**
 * One pending conversation aggregated from notifications.
 *
 * Notifications from the same conversation overwrite each other, so the list
 * holds the newest line per conversation — which is what "what needs a reply
 * now" actually asks for.
 *
 * [contentIntent] is the notification's own tap action; sending it jumps
 * straight back into that conversation. It lives outside the constructor so it
 * never takes part in equality or in any persisted form.
 */
data class MsgItem(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val timeMs: Long
) {
    /** 1–9 once judged; 0 while unknown. */
    var urgency: Int = 0
    var needsNow: Boolean = false
    var confidence: Double = 0.0
    var category: String = ""
    var reason: String = ""
    var judged: Boolean = false
    var judging: Boolean = false
    var error: String? = null
    var contentIntent: PendingIntent? = null

    /**
     * The StatusBarNotification key, kept so the overlay can cancel exactly this
     * notification. Independent from [key], which is our own per-conversation id.
     */
    var sbnKey: String = ""

    val bucket: Bucket
        get() = when {
            !judged -> Bucket.PENDING
            needsNow || urgency >= 7 -> Bucket.NOW
            urgency >= 4 -> Bucket.SOON
            else -> Bucket.LATER
        }
}

enum class Bucket { NOW, SOON, LATER, PENDING }

/** Short labels for the reason Jev picked, kept here so the UI never branches on raw ids. */
object ReasonLabel {
    private val map = mapOf(
        "deadline" to "有明确时限",
        "someone_waiting" to "对方在等",
        "emotional" to "需要安抚",
        "money_risk" to "涉钱/安全",
        "none" to "没有压力"
    )

    fun of(id: String): String = map[id] ?: id
}

/** Short labels for the category Jev picked. */
object CategoryLabel {
    private val map = mapOf(
        "work_blocking" to "工作",
        "personal" to "私人",
        "logistics" to "约定",
        "info_only" to "通知",
        "promotion" to "推广",
        "system" to "系统",
        "money" to "钱"
    )

    fun of(id: String): String = map[id] ?: id
}

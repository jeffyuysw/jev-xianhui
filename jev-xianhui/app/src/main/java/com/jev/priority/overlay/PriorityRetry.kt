package com.jev.priority.overlay

import android.content.Context
import com.jev.priority.core.MsgItem
import com.jev.priority.core.MsgStore
import com.jev.priority.core.Prefs
import com.jev.priority.jev.PriorityClient
import kotlin.concurrent.thread

/**
 * Re-judges a single row off the main thread.
 *
 * Kept separate from the notification listener so the overlay can offer a
 * retry without knowing anything about how judging works.
 */
internal object PriorityRetry {

    fun judge(ctx: Context, item: MsgItem) {
        val prefs = Prefs(ctx)
        if (prefs.apiKey.isBlank()) {
            item.judging = false
            item.error = "未配置 API Key"
            MsgStore.notifyChange()
            return
        }
        thread(name = "jev-retry") {
            try {
                PriorityClient(prefs).judge(item)
            } catch (e: Exception) {
                item.error = e.message ?: "判断失败"
            } finally {
                item.judging = false
                MsgStore.notifyChange()
            }
        }
    }
}

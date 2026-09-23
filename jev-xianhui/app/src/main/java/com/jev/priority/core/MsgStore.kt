package com.jev.priority.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory list of pending conversations. Nothing is persisted: this is a
 * triage surface for what arrived recently, not a message archive.
 *
 * Listeners are a list rather than a single slot because both the overlay and
 * the main screen want to react to changes — a single slot would silently drop
 * whichever registered first.
 */
object MsgStore {

    private val lock = Any()
    private val items = LinkedHashMap<String, MsgItem>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun upsert(item: MsgItem) {
        synchronized(lock) { items[item.key] = item }
        notifyChange()
    }

    fun remove(key: String) {
        synchronized(lock) { items.remove(key) }
        notifyChange()
    }

    fun clear() {
        synchronized(lock) { items.clear() }
        notifyChange()
    }

    fun size(): Int = synchronized(lock) { items.size }

    fun countNow(): Int = all().count { it.bucket == Bucket.NOW }

    fun all(): List<MsgItem> = synchronized(lock) { items.values.toList() }

    /**
     * Judged items by urgency first, then unjudged by arrival time. A message
     * we have not scored yet must never outrank one we know is urgent.
     */
    fun sorted(): List<MsgItem> {
        val list = all()
        val judged = list.filter { it.judged }
            .sortedWith(compareByDescending<MsgItem> { it.needsNow }
                .thenByDescending { it.urgency }
                .thenByDescending { it.timeMs })
        val pending = list.filter { !it.judged }.sortedByDescending { it.timeMs }
        return judged + pending
    }

    /** Call from any thread; listeners run on the main thread. */
    fun notifyChange() {
        main.post { listeners.forEach { it() } }
    }
}

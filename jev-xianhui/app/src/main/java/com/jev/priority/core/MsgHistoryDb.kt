package com.jev.priority.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 判断记录，**只存在本机**。
 *
 * 放在应用私有目录的 SQLite 里（`getDatabasePath`），不需要任何存储权限，
 * 也不会随备份外流（manifest 里 allowBackup=false）。
 *
 * 为什么用数据库而不是内存列表：记录要能翻很久以前的，而且要分页取
 * 「最近 20 条 → 再往下 20 条」，内存列表做不到，也不该一直占着内存。
 *
 * 记录时机：收到通知先落一条（状态=判断中），判断完成后再回填结果。
 * 这样「判断失败」也会留下痕迹，用户能看出是没判断还是判断错了。
 */
class MsgHistoryDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app_name TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                status INTEGER NOT NULL,
                urgency INTEGER NOT NULL DEFAULT 0,
                needs_now INTEGER NOT NULL DEFAULT 0,
                category TEXT NOT NULL DEFAULT '',
                reason TEXT NOT NULL DEFAULT '',
                time_ms INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // 列表永远按时间倒序翻页，索引照着查询建。
        db.execSQL("CREATE INDEX idx_time ON $TABLE(time_ms DESC, id DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有 v1；将来加字段时在这里按版本迁移，别 drop 掉用户记录。
    }

    /** 收到通知时先落一条，返回行 id 供判断完成后回填。 */
    fun insertPending(item: MsgItem): Long {
        val cv = ContentValues().apply {
            put("pkg", item.pkg)
            put("app_name", item.appName)
            put("title", item.title)
            put("body", item.text)
            put("status", STATUS_PENDING)
            put("time_ms", item.timeMs)
            put("created_at", System.currentTimeMillis())
        }
        return runCatching { writableDatabase.insert(TABLE, null, cv) }.getOrDefault(-1L)
    }

    /** 判断完成后回填结果。 */
    fun fillResult(id: Long, item: MsgItem) {
        if (id <= 0) return
        val status = when {
            item.judged -> STATUS_JUDGED
            item.error != null -> STATUS_FAILED
            else -> STATUS_PENDING
        }
        val cv = ContentValues().apply {
            put("status", status)
            put("urgency", item.urgency)
            put("needs_now", if (item.needsNow) 1 else 0)
            put("category", item.category)
            put("reason", item.reason)
        }
        runCatching { writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id.toString())) }
    }

    /**
     * 倒序取一页。[offset] 是已经看过的条数，[filter] 是状态筛选。
     *
     * 排序用 (time_ms, id) 双字段：同一毫秒到达的多条消息也能稳定排序，
     * 否则翻页时可能出现重复或漏条。
     */
    fun page(offset: Int, limit: Int, filter: Filter = Filter.NOW): List<Record> {
        val out = ArrayList<Record>(limit)
        val where = filter.whereSql?.let { " WHERE $it" }.orEmpty()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT id, app_name, title, body, status, urgency, needs_now, " +
                    "category, reason, time_ms FROM $TABLE$where " +
                    "ORDER BY time_ms DESC, id DESC LIMIT ? OFFSET ?",
                arrayOf(limit.toString(), offset.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Record(
                            id = c.getLong(0),
                            appName = c.getString(1),
                            title = c.getString(2),
                            body = c.getString(3),
                            status = c.getInt(4),
                            urgency = c.getInt(5),
                            needsNow = c.getInt(6) == 1,
                            category = c.getString(7),
                            reason = c.getString(8),
                            timeMs = c.getLong(9),
                        )
                    )
                }
            }
        }
        return out
    }

    /** 某个筛选下的总条数，用于底部提示。 */
    fun count(filter: Filter = Filter.NOW): Int = runCatching {
        val where = filter.whereSql?.let { " WHERE $it" }.orEmpty()
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE$where", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /**
     * 只保留最近 [keep] 条。
     *
     * 记录是纯文本，量级很小，但也不能无限涨 —— 每次插入后顺手裁一次，
     * 成本是一个 DELETE，可忽略。
     */
    fun trim(keep: Int) {
        runCatching {
            writableDatabase.execSQL(
                "DELETE FROM $TABLE WHERE id NOT IN " +
                    "(SELECT id FROM $TABLE ORDER BY time_ms DESC, id DESC LIMIT ?)",
                arrayOf(keep)
            )
        }
    }

    fun clear() {
        runCatching { writableDatabase.delete(TABLE, null, null) }
    }

    /** 一条记录，已经整理成列表直接能用的样子。 */
    data class Record(
        val id: Long,
        val appName: String,
        val title: String,
        val body: String,
        val status: Int,
        val urgency: Int,
        val needsNow: Boolean,
        val category: String,
        val reason: String,
        val timeMs: Long,
    ) {
        val judged: Boolean get() = status == STATUS_JUDGED
        val failed: Boolean get() = status == STATUS_FAILED
    }

    /**
     * 记录页的筛选。
     *
     * 分档条件必须和 [MsgItem.bucket] 完全一致，否则「记录页说马上回、
     * 悬浮窗说尽快」这种自相矛盾就会冒出来。SQL 里用的是 status 字段的
     * 字面量（0 判断中 / 1 已判断 / 2 失败），因为枚举常量在 companion
     * 初始化顺序上不可靠。
     */
    enum class Filter(val label: String, val whereSql: String?) {
        NOW("马上回", "status = 1 AND (needs_now = 1 OR urgency >= 7)"),
        SOON("尽快", "status = 1 AND needs_now = 0 AND urgency >= 4 AND urgency < 7"),
        LATER("可晚点", "status = 1 AND needs_now = 0 AND urgency < 4"),
        PENDING("判断中", "status = 0"),
        FAILED("失败", "status = 2"),
        ALL("全部", null),
    }

    companion object {
        private const val DB_NAME = "jev_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "history"

        const val STATUS_PENDING = 0
        const val STATUS_JUDGED = 1
        const val STATUS_FAILED = 2

        /** 上限 2000 条，够翻很久，也不会让库无限膨胀。 */
        const val MAX_RECORDS = 2000

        @Volatile
        private var instance: MsgHistoryDb? = null

        /**
         * 单例：监听服务、悬浮窗重试、记录页三处都会写这个库，
         * 各建一个 SQLiteOpenHelper 会互相抢锁，统一走这里。
         */
        fun get(context: Context): MsgHistoryDb =
            instance ?: synchronized(this) {
                instance ?: MsgHistoryDb(context.applicationContext).also { instance = it }
            }
    }
}

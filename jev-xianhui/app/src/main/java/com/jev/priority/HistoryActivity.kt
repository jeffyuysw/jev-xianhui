package com.jev.priority

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.priority.core.CategoryLabel
import com.jev.priority.core.MsgHistoryDb
import com.jev.priority.core.ReasonLabel
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分析记录：判断过的每一条都留在这里。
 *
 * 数据来自本机 SQLite（[MsgHistoryDb]），不联网、不上传。列表按时间倒序，
 * 一次取 20 条；滚到底部自动续下一页，避免一次性把几千条读进内存。
 *
 * 顶部一排状态筛选，**默认只看「马上回」** —— 这个页面最常被打开的理由
 * 就是「刚才那条急事是什么来着」，而不是翻全部流水。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var db: MsgHistoryDb
    private lateinit var lvHistory: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvFooter: TextView

    /** 筛选 chip 与它对应的筛选值，顺序和布局里一致。 */
    private lateinit var chips: List<Pair<MsgHistoryDb.Filter, TextView>>

    private val items = ArrayList<MsgHistoryDb.Record>()
    private val adapter = HistoryAdapter()

    /** 当前筛选。默认「马上回」。 */
    private var filter = MsgHistoryDb.Filter.NOW

    private var loading = false
    private var hasMore = true

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        db = MsgHistoryDb.get(this)
        lvHistory = findViewById(R.id.lvHistory)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvFooter = findViewById(R.id.tvFooter)

        lvHistory.adapter = adapter
        // 快到底（还剩 2 条）就开始取下一页，滚动手感上不会出现空白等待。
        lvHistory.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int,
            ) {
                if (totalCount > 0 && firstVisible + visibleCount >= totalCount - 2) {
                    loadMore()
                }
            }
        })

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnClearHistory).setOnClickListener { confirmClear() }

        buildFilters()
        loadMore()
    }

    // -- 筛选 ----------------------------------------------------------------

    private fun buildFilters() {
        chips = listOf(
            MsgHistoryDb.Filter.NOW to findViewById(R.id.chipNow),
            MsgHistoryDb.Filter.SOON to findViewById(R.id.chipSoon),
            MsgHistoryDb.Filter.LATER to findViewById(R.id.chipLater),
            MsgHistoryDb.Filter.PENDING to findViewById(R.id.chipPending),
            MsgHistoryDb.Filter.FAILED to findViewById(R.id.chipFailed),
            MsgHistoryDb.Filter.ALL to findViewById(R.id.chipAll),
        )
        chips.forEach { (f, tv) -> tv.setOnClickListener { selectFilter(f) } }
        refreshFilterStyles()
    }

    private fun refreshFilterStyles() {
        chips.forEach { (f, tv) ->
            val selected = f == filter
            tv.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#5F5E5A"))
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_chip_filter_on else R.drawable.bg_chip_filter_off
            )
        }
    }

    /** 换筛选：列表整体重来，分页从第一页开始。 */
    private fun selectFilter(f: MsgHistoryDb.Filter) {
        if (f == filter) return
        filter = f
        refreshFilterStyles()
        items.clear()
        adapter.notifyDataSetChanged()
        hasMore = true
        loading = false
        tvFooter.text = ""
        loadMore()
    }

    // -- 列表 ----------------------------------------------------------------

    private fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        if (items.isEmpty()) tvFooter.text = "加载中…"

        val current = filter
        thread(name = "history-load") {
            val page = db.page(items.size, PAGE_SIZE, current)
            val total = db.count(current)
            runOnUiThread {
                // 用户在加载过程中切了筛选，这批数据就作废。
                if (current != filter) return@runOnUiThread
                items.addAll(page)
                adapter.notifyDataSetChanged()
                hasMore = page.size == PAGE_SIZE
                loading = false
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) {
                    tvEmpty.text = if (filter == MsgHistoryDb.Filter.ALL) {
                        "还没有记录\n收到消息后，这里会留下判断过的每一条"
                    } else {
                        "没有「${filter.label}」的记录"
                    }
                }
                tvFooter.text = when {
                    items.isEmpty() -> ""
                    hasMore -> "继续下滑加载更多（共 $total 条）"
                    else -> "已经到底了，共 $total 条"
                }
            }
        }
    }

    private fun confirmClear() {
        thread {
            val all = db.count(MsgHistoryDb.Filter.ALL)
            runOnUiThread {
                if (all == 0) {
                    Toast.makeText(this, "还没有记录", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("清空分析记录？")
                    .setMessage("本机保存的 $all 条记录会被全部删除，无法恢复。")
                    .setPositiveButton("清空") { _, _ ->
                        thread {
                            db.clear()
                            runOnUiThread {
                                items.clear()
                                adapter.notifyDataSetChanged()
                                hasMore = true
                                tvEmpty.visibility = View.VISIBLE
                                tvEmpty.text = "还没有记录\n收到消息后，这里会留下判断过的每一条"
                                tvFooter.text = ""
                                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /** 状态徽章的文字与颜色。 */
    private fun statusOf(r: MsgHistoryDb.Record): Pair<String, Int> = when {
        r.failed -> "判断失败" to Color.parseColor("#A32D2D")
        !r.judged -> "判断中" to Color.parseColor("#3B7DD8")
        r.needsNow || r.urgency >= 7 -> "马上回" to Color.parseColor("#D6453D")
        r.urgency >= 4 -> "尽快" to Color.parseColor("#D98A1F")
        else -> "可晚点" to Color.parseColor("#9B9A94")
    }

    private fun metaOf(r: MsgHistoryDb.Record): String {
        val time = timeFmt.format(Date(r.timeMs))
        return when {
            r.failed -> "$time · 判断未完成"
            !r.judged -> "$time · 正在判断"
            else -> buildString {
                append(time)
                append(" · 紧急 ").append(r.urgency).append("/9")
                if (r.reason.isNotBlank()) append(" · ").append(ReasonLabel.of(r.reason))
                if (r.category.isNotBlank()) append(" · ").append(CategoryLabel.of(r.category))
            }
        }
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
            val r = items[position]

            v.findViewById<TextView>(R.id.tvSource).text = r.appName
            v.findViewById<TextView>(R.id.tvTitle).text = r.title
            v.findViewById<TextView>(R.id.tvBody).text = r.body

            val (label, color) = statusOf(r)
            v.findViewById<TextView>(R.id.tvStatus).apply {
                text = label
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(color)
                    cornerRadius = dp(8).toFloat()
                }
            }
            v.findViewById<TextView>(R.id.tvMeta).text = metaOf(r)
            return v
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        /** 一页 20 条 —— 用户明确要求的默认值。 */
        private const val PAGE_SIZE = 20
    }
}

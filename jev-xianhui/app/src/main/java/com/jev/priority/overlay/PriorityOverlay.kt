package com.jev.priority.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.priority.core.Bucket
import com.jev.priority.core.CategoryLabel
import com.jev.priority.core.MsgItem
import com.jev.priority.core.MsgStore
import com.jev.priority.core.Prefs
import com.jev.priority.core.ReasonLabel
import com.jev.priority.notify.NotificationListenerHolder
import kotlin.math.abs

/**
 * The draggable triage bubble.
 *
 * Visual states:
 *  - **Expanded**: a rounded card with a header and a scrollable list. Drag it
 *    by the header; the whole row is tappable, not just the text.
 *  - **Collapsed**: just the header strip, with a live count.
 *
 * Dragging snaps to whichever screen edge is nearer, so the bubble never parks
 * in the middle of the screen and blocks content.
 *
 * Colours, font size, width and opacity all come from [Prefs]. Backgrounds are
 * built at runtime rather than loaded from XML because the palette is
 * user-chosen.
 *
 * ## Threading
 * Every view mutation happens on the main thread via [postOnMain]. The store is
 * mutated by the notification listener (also main thread, but not guaranteed in
 * future), so all entry points marshall defensively.
 *
 * ## The rebuild rule
 * Appearance lives in [Prefs], which is read only when building views. So a
 * settings change requires a rebuild — but a rebuild must NEVER be triggered
 * from inside [show], or the two call each other forever. See [applyAppearance].
 */
class PriorityOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)

    private var params: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var listHost: LinearLayout? = null
    private var titleText: TextView? = null
    private var countBadge: TextView? = null
    private var bodyCard: View? = null

    /** The list's ScrollView, held directly. Never re-derived via the tree. */
    private var scroller: ScrollView? = null
    private var footer: TextView? = null
    private var header: LinearLayout? = null

    /** Palette for the current appearance. Rebuilt only by [applyAppearance]. */
    private var pal = OverlayPalette(prefs.overlayBg, prefs.overlayFg)

    /** Distinguishes "user collapsed it" from "not shown yet". */
    private var expanded = true

    fun isShowing(): Boolean = root != null

    /** True while a rebuild is in flight, so re-entrant calls become no-ops. */
    private var rebuilding = false

    private fun postOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private val refreshListener: () -> Unit = { refresh() }

    private fun screenWidth(): Int = ctx.resources.displayMetrics.widthPixels

    fun show() = postOnMain {
        if (root != null || rebuilding) return@postOnMain
        rebuildLocked()
    }

    fun hide() = postOnMain {
        if (rebuilding) return@postOnMain
        detach()
    }

    /** Removes the view and unregisters the listener. Safe to call twice. */
    private fun detach() {
        val v = root ?: return
        runCatching { wm.removeView(v) }
        root = null
        params = null
        MsgStore.removeListener(refreshListener)
        prefs.overlayShown = false
    }

    /** Creates the view tree and attaches it. Assumes the caller is on main. */
    private fun rebuildLocked() {
        rebuilding = true
        try {
            val p = makeParams()
            val view = build()
            // Reset per-tree references that build() just repopulated.
            runCatching { wm.addView(view, p) }.onFailure { e ->
                android.util.Log.w(TAG, "addView failed: ${e.message}")
                Toast.makeText(ctx, "悬浮窗权限未开启", Toast.LENGTH_LONG).show()
                rebuilding = false
                return
            }
            params = p
            root = view
            prefs.overlayShown = true
            MsgStore.addListener(refreshListener)
            refresh()
        } finally {
            rebuilding = false
        }
    }

    private fun makeParams() = WindowManager.LayoutParams().apply {
        width = dp(widthDp())
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // ONLY these two flags — deliberately. Four earlier attempts at
        // "correctness" here broke touch entirely:
        //   FLAG_WATCH_OUTSIDE_TOUCH  → the window swallows ACTION_OUTSIDE and
        //     the header's onTouch (which returns true on ACTION_DOWN) stops
        //     delivering taps to the child button TextViews. Symptoms: nothing
        //     clickable, "—" and "×" dead.
        //   FLAG_NOT_TOUCH_MODAL      → with the above, touches near the window
        //     edge are consumed instead of passing through.
        // NOT_FOCUSABLE keeps the keyboard with the app underneath; that is the
        // only thing this window needs. Do not add to this list casually.
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        x = if (prefs.overlayX >= 0) prefs.overlayX else dp(6)
        y = if (prefs.overlayY >= 0) prefs.overlayY else dp(160)
        // Window alpha fades the entire bubble uniformly (fill, text, bars).
        // Per-view alpha would let overlapping children double up and show
        // seams, so use the window-level value.
        alpha = opacityFraction()
    }

    /** 1.0 = opaque, 0.01 = barely there. Never returns 0. */
    private fun opacityFraction(): Float =
        (prefs.overlayOpacity.coerceIn(Prefs.MIN_OPACITY, Prefs.MAX_OPACITY) / 100f)

    /** Nearest-edge snap: the bubble parks at the left or right margin. */
    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val center = p.x + dp(widthDp()) / 2
        p.x = if (center < screenWidth() / 2) dp(EDGE_MARGIN_DP)
        else screenWidth() - dp(widthDp()) - dp(EDGE_MARGIN_DP)
    }

    private fun widthDp(): Int = prefs.overlayWidth

    /** Body text size in sp, from settings. */
    private fun fontSp(): Float = prefs.overlayFontSize.toFloat()

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ---- the card: ONE surface, header included ----
        // The header shares the card's background on purpose: no separate block,
        // so the title sits visually attached to the first row.
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bodyCard = card

        // ---- header ----
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        header = headerRow

        val title = TextView(ctx).apply {
            text = "先回"
            setTextColor(pal.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() + 1f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        titleText = title

        val badge = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() - 2f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }
        countBadge = badge

        headerRow.addView(title)
        headerRow.addView(badge)
        headerRow.addView(actionButton("—") {
            expanded = false
            refresh()
        })
        headerRow.addView(actionButton("×") { hide() })

        // Drag handler.
        //
        // IMPORTANT: this must not swallow taps meant for the "—" / "×" buttons
        // that live inside this same row. An OnTouchListener returning true on
        // ACTION_DOWN consumes the gesture before children see it, which is
        // exactly how those two buttons stop working.
        //
        // So: on DOWN we tentatively grab the gesture, but we only ever handle
        // the MOVE stream. On UP we check whether the touch landed on a child
        // button; if it did, we replay it to that child instead of toggling.
        headerRow.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            /** The "—"/"×" button under the finger, if any. */
            private fun childAt(rawX: Float, rawY: Float): View? {
                val loc = IntArray(2)
                for (i in 0 until headerRow.childCount) {
                    val c = headerRow.getChildAt(i)
                    if (!c.isClickable) continue
                    c.getLocationOnScreen(loc)
                    val l = loc[0].toFloat()
                    val t = loc[1].toFloat()
                    if (rawX >= l && rawX <= l + c.width &&
                        rawY >= t && rawY <= t + c.height
                    ) return c
                }
                return null
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        // If the finger is on a button, let the button own the
                        // gesture — do not claim it here.
                        if (childAt(event.rawX, event.rawY) != null) return false
                        v.isPressed = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) moved = true
                        if (moved) {
                            v.isPressed = false
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(root, p) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        if (moved) {
                            snapToEdge(p)
                            runCatching { wm.updateViewLayout(root, p) }
                            prefs.overlayX = p.x
                            prefs.overlayY = p.y
                        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                            // Release on a button → forward the click.
                            val child = childAt(event.rawX, event.rawY)
                            if (child != null) {
                                child.performClick()
                            } else {
                                // A clean tap on the bar toggles the list.
                                expanded = !expanded
                                refresh()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        // ---- list ----
        // Held in a field so refresh() never has to walk the tree to find it.
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(LIST_HEIGHT_DP)
            )
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        scroller = scroll

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Top padding 0 so the first row starts right under the divider.
            setPadding(dp(6), 0, dp(6), dp(4))
        }
        listHost = list
        scroll.addView(list)

        val foot = TextView(ctx).apply {
            text = "全部已读"
            setTextColor(pal.textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() - 1f)
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener {
                if (MsgStore.size() == 0) return@setOnClickListener
                val listener = NotificationListenerHolder.instance
                MsgStore.all().forEach { item ->
                    listener?.dismissByKey(item.sbnKey, item.key)
                }
                MsgStore.clear()
                Toast.makeText(ctx, "已全部标记为处理", Toast.LENGTH_SHORT).show()
            }
        }
        footer = foot

        card.addView(headerRow)
        card.addView(divider())
        card.addView(scroll)
        card.addView(divider())
        card.addView(foot)

        outer.addView(card)
        return outer
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextColor(pal.textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() + 3f)
            gravity = Gravity.CENTER
            isClickable = true
            // A comfortable touch target: the glyph is small but the box is not.
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(ctx).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        )
    }

    /** The single rounded surface: fill + hairline, radius 14dp. */
    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(pal.bg)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), pal.border)
    }

    private fun refresh() {
        postOnMain {
            val host = listHost ?: return@postOnMain
            val items = MsgStore.sorted()
            val urgent = MsgStore.countNow()

            titleText?.apply {
                text = when {
                    urgent > 0 -> "要马上回"
                    items.isEmpty() -> "先回"
                    else -> "待处理"
                }
                setTextColor(pal.fg)
            }

            // Count badge: accent colour only when something urgent is waiting.
            countBadge?.apply {
                if (items.isEmpty()) {
                    visibility = View.GONE
                } else {
                    val chip = if (urgent > 0) pal.accent(Bucket.NOW) else pal.accent(Bucket.LATER)
                    visibility = View.VISIBLE
                    text = " $urgent "
                    setTextColor(pal.on(chip))
                    background = rounded(chip, 9)
                }
            }

            // The list is the ONLY thing collapse hides. The header stays, which
            // is the whole point of collapsing.
            scroller?.visibility = if (expanded) View.VISIBLE else View.GONE

            host.removeAllViews()
            if (!expanded) return@postOnMain
            if (items.isEmpty()) {
                host.addView(emptyState())
                fitScroller(host, EMPTY_LIST_HEIGHT_DP)
                return@postOnMain
            }
            items.forEach { host.addView(row(it)) }
            fitScroller(host, LIST_MAX_HEIGHT_DP)
        }
    }

    /**
     * Shrinks the scroller to its content, up to [maxDp].
     *
     * The ScrollView used to hold a flat 300dp, so an empty or one-message
     * overlay showed a tall empty card. Measuring the child after layout and
     * clamping gives a card that grows with the queue and stops at the cap,
     * where it starts scrolling instead.
     *
     * The measure pass has to happen after the children are laid out, so it is
     * queued with `post` rather than run inline (widths would still be 0).
     */
    private fun fitScroller(host: LinearLayout, maxDp: Int) {
        val sc = scroller ?: return
        sc.post {
            val lm = sc.layoutParams ?: return@post
            val child = if (sc.childCount > 0) sc.getChildAt(0) else null
            if (child == null) return@post
            child.measure(
                View.MeasureSpec.makeMeasureSpec(sc.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val wanted = child.measuredHeight.coerceAtMost(dp(maxDp))
            if (lm.height != wanted) {
                lm.height = wanted
                sc.layoutParams = lm
            }
        }
    }

    /**
     * The idle placeholder.
     *
     * Deliberately a single short line with tight vertical padding: an idle
     * overlay should read as a slim strip, not a big empty card. Two lines with
     * 22dp padding used to make it nearly as tall as a real message row.
     */
    private fun emptyState(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        addView(TextView(ctx).apply {
            text = "暂时没有待处理的消息"
            setTextColor(pal.textFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() - 1f)
            gravity = Gravity.CENTER
        })
    }

    private fun row(item: MsgItem): View {
        val accent = pal.accent(item.bucket)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rowBackground(item.bucket)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        }

        val bar = View(ctx).apply {
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(
                dp(3), LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setPadding(dp(8), 0, 0, 0)
        }

        val line1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(ctx).apply {
            text = item.title
            setTextColor(pal.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() + 1f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        line1.addView(TextView(ctx).apply {
            text = bucketLabel(item)
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() - 2f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        })

        body.addView(line1)
        // Message body at full strength — this is the line people read.
        body.addView(TextView(ctx).apply {
            text = item.text
            setTextColor(pal.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })

        val metaText = buildString {
            if (item.judged) {
                append("紧急 ").append(item.urgency).append("/9")
                if (item.reason.isNotBlank()) append(" · ").append(ReasonLabel.of(item.reason))
                if (item.category.isNotBlank()) append(" · ").append(CategoryLabel.of(item.category))
            } else if (item.error != null) {
                append("判断失败，点重试")
            } else {
                append("正在判断…")
            }
        }
        body.addView(TextView(ctx).apply {
            text = metaText
            setTextColor(pal.textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() - 1f)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(bar)
        row.addView(body)

        // The whole row is the touch target, not just the text.
        row.setOnClickListener { onRowTapped(item) }
        return row
    }

    private fun bucketLabel(item: MsgItem): String = when (item.bucket) {
        Bucket.NOW -> "马上回"
        Bucket.SOON -> "尽快"
        Bucket.LATER -> "可晚点"
        Bucket.PENDING -> if (item.judging) "判断中" else "待判断"
    }

    private fun onRowTapped(item: MsgItem) {
        // A failed judgment has no useful action other than retrying.
        if (!item.judged && item.error != null) {
            item.error = null
            item.judging = true
            MsgStore.notifyChange()
            PriorityRetry.judge(ctx, item)
            return
        }

        val intent = item.contentIntent
        if (intent == null) {
            Toast.makeText(ctx, "这条没有可跳转的入口", Toast.LENGTH_SHORT).show()
            return
        }
        if (!runCatching { intent.send() }.isSuccess) {
            Toast.makeText(ctx, "跳转失败", Toast.LENGTH_SHORT).show()
            return
        }

        // Tapping means "I am dealing with this" — drop the row and clear the
        // shade notification so it does not sit in two places.
        MsgStore.remove(item.key)
        NotificationListenerHolder.instance?.dismissByKey(item.sbnKey, item.key)
    }

    /** Rounded rect in the given colour, used for chips and dots. */
    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /**
     * Row fill: the palette's row tint with a faint accent wash on the left, so
     * the bucket reads even before you notice the colour bar.
     */
    private fun rowBackground(bucket: Bucket): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(pal.accentChip(bucket), pal.rowBg, pal.rowBg)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
        }

    // -- appearance ---------------------------------------------------------

    /**
     * Applies just the opacity, without rebuilding the view tree.
     *
     * Dragging the opacity slider fires continuously, so a full rebuild per
     * frame would flicker and lose scroll position. Updating the live window
     * params is enough and stays smooth.
     */
    fun applyOpacity() = postOnMain {
        val p = params ?: return@postOnMain
        p.alpha = opacityFraction()
        runCatching { wm.updateViewLayout(root, p) }
    }

    /**
     * Re-reads appearance settings and rebuilds the overlay if it is showing.
     *
     * This is the ONLY place that rebuilds for appearance reasons. It must never
     * be called from [show] — the two would recurse.
     */
    fun applyAppearance() = postOnMain {
        pal = OverlayPalette(prefs.overlayBg, prefs.overlayFg)
        if (root == null || rebuilding) return@postOnMain

        val wasExpanded = expanded
        val pos = params
        detach()
        expanded = wasExpanded
        rebuildLocked()
        // Restore the on-screen position across the rebuild.
        if (pos != null) {
            params?.let { np ->
                np.x = pos.x
                np.y = pos.y
                runCatching { wm.updateViewLayout(root, np) }
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "JEVPRIORITY"

        /** Initial height of the scroller. fitScroller() adjusts it from here. */
        private const val LIST_HEIGHT_DP = 300

        /** Hard ceiling; above this the list scrolls instead of growing. */
        private const val LIST_MAX_HEIGHT_DP = 300

        /**
         * Height of the list area when nothing is queued. Small on purpose: an
         * idle overlay should read as a slim strip, not a big empty card.
         */
        private const val EMPTY_LIST_HEIGHT_DP = 40

        private const val EDGE_MARGIN_DP = 6
        private const val TOUCH_SLOP = 8f
    }
}

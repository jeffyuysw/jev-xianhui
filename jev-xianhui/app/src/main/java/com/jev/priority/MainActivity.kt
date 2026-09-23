package com.jev.priority

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jev.priority.core.MsgStore
import com.jev.priority.core.Prefs
import com.jev.priority.jev.PriorityClient
import com.jev.priority.notify.KeepAliveService
import com.jev.priority.notify.MsgNotificationListener
import com.jev.priority.notify.NotificationListenerHolder
import com.jev.priority.overlay.OverlayHolder
import com.jev.priority.overlay.OverlayPalette
import com.jev.priority.overlay.PriorityOverlay
import kotlin.concurrent.thread

/**
 * One screen, three setup steps.
 *
 * Each step is a card that reflects its own state: the chip flips from red
 * "not yet" to green "done", and the button disappears once there is nothing
 * left to do. That stops people tapping a button that no longer has any effect.
 *
 * Both permissions are special app-access permissions Android only grants from
 * the system settings screen, so this activity can detect the state and send
 * the user there, but cannot grant them itself — by design, not a shortcut.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: PriorityOverlay

    private lateinit var tvStatusSummary: TextView
    private lateinit var statusDot: View
    private lateinit var tvListenerStatus: TextView
    private lateinit var tvNotifyStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvKeyStatus: TextView
    private lateinit var tvTestResult: TextView
    private lateinit var etKey: EditText
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnAutostart: Button
    private lateinit var btnNotifyPerm: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnToggle: Button

    /**
     * Android 13+ needs POST_NOTIFICATIONS at runtime, otherwise the keep-alive
     * foreground notification is suppressed and the ROM has even more reason to
     * freeze us. Asked once per process so a denial cannot become a resume loop.
     */
    private var askedPostNotifications = false
    private val askPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "不授权也能用，但后台更容易被系统回收", Toast.LENGTH_LONG).show()
            }
        }

    /**
     * 权限状态实时刷新。
     *
     * 只在 onResume 里查一次是不够的：从系统设置授权页返回的瞬间，ROM（小米 / HyperOS
     * 尤其明显）对 AppOps 的写入是异步的，立刻查询拿到的还是旧值 —— 表现为「明明开了
     * 悬浮窗，卡片却显示未开启，退出重开才好」。所以：
     *  - 悬浮窗：注册 AppOps 回调，权限一变系统就通知我们；
     *  - 通知使用权：监听 enabled_notification_listeners 这个 Secure 设置的变化；
     *  - 再配合 onResume 里 300ms / 1200ms / 3000ms 三次兜底刷新，覆盖回调缺失的 ROM。
     *
     * AppOps 的回调在 binder 线程，统一 post 到主线程处理。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val overlayOpsListener = object : AppOpsManager.OnOpChangedListener {
        override fun onOpChanged(op: String, packageName: String) = onPermissionMaybeChanged()
    }

    private val notifyObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onPermissionMaybeChanged()
    }

    private fun onPermissionMaybeChanged() {
        if (isDestroyed || isFinishing) return
        val overlayOn = Settings.canDrawOverlays(this)
        // 权限被收回时把悬浮窗收掉，避免残留一块点不动也关不掉的窗体。
        if (!overlayOn && overlay.isShowing()) overlay.hide()
        if (notificationAccessGranted()) {
            MsgNotificationListener.rebind(this)
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
            }
        }
        mainHandler.post { syncStates() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        // 单例 + applicationContext：悬浮窗的寿命比 Activity 长（系统级窗体，
        // 用户不关就一直在），持有 Activity 会让整个页面无法回收。
        overlay = OverlayHolder.get(this)

        tvStatusSummary = findViewById(R.id.tvStatusSummary)
        statusDot = findViewById(R.id.statusDot)
        tvListenerStatus = findViewById(R.id.tvListenerStatus)
        tvNotifyStatus = findViewById(R.id.tvNotifyStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvKeyStatus = findViewById(R.id.tvKeyStatus)
        tvTestResult = findViewById(R.id.tvTestResult)
        etKey = findViewById(R.id.etApiKey)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnAutostart = findViewById(R.id.btnAutostart)
        btnNotifyPerm = findViewById(R.id.btnNotifyPerm)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        btnToggle = findViewById(R.id.btnToggleOverlay)

        etKey.setText(prefs.apiKey)

        runCatching {
            findViewById<TextView>(R.id.tvVersion).text =
                "版本 ${packageManager.getPackageInfo(packageName, 0).versionName}"
        }

        btnNotifyPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnOverlayPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val typed = etKey.text.toString().trim()
            prefs.apiKey = typed
            tvTestResult.visibility = View.GONE
            Toast.makeText(
                this,
                if (typed.isBlank()) "已清空 Key" else "已保存",
                Toast.LENGTH_SHORT
            ).show()
            syncStates()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            prefs.apiKey = etKey.text.toString().trim()
            if (prefs.apiKey.isBlank()) {
                showTest("先填 API Key 再测试", ok = false)
                return@setOnClickListener
            }
            showTest("正在连接模型服务…", ok = true)
            thread {
                val (msg, ok) = try {
                    PriorityClient(prefs).probe() to true
                } catch (e: Exception) {
                    "连接失败：${e.message ?: "未知错误"}" to false
                }
                runOnUiThread {
                    showTest(msg, ok)
                    syncStates()
                }
            }
        }

        val cbWechat = findViewById<CheckBox>(R.id.cbWechat)
        val cbQQ = findViewById<CheckBox>(R.id.cbQQ)
        cbWechat.isChecked = Prefs.WECHAT in prefs.watchPkgs
        cbQQ.isChecked = Prefs.QQ in prefs.watchPkgs
        val syncWatch = {
            val set = mutableSetOf<String>()
            if (cbWechat.isChecked) set.add(Prefs.WECHAT)
            if (cbQQ.isChecked) set.add(Prefs.QQ)
            prefs.watchPkgs = set
        }
        cbWechat.setOnCheckedChangeListener { _, _ -> syncWatch() }
        cbQQ.setOnCheckedChangeListener { _, _ -> syncWatch() }

        val cbAuto = findViewById<CheckBox>(R.id.cbAutoJudge)
        cbAuto.isChecked = prefs.autoJudge
        cbAuto.setOnCheckedChangeListener { _, v -> prefs.autoJudge = v }

        // 是否后台监听。默认关闭 —— 关掉时，停在微信里收到的消息也会进列表。
        val cbBackgroundOnly = findViewById<CheckBox>(R.id.cbBackgroundOnly)
        val tvBackgroundHint = findViewById<TextView>(R.id.tvBackgroundHint)
        cbBackgroundOnly.isChecked = prefs.backgroundOnly
        refreshBackgroundHint(tvBackgroundHint)
        cbBackgroundOnly.setOnCheckedChangeListener { _, v ->
            prefs.backgroundOnly = v
            refreshBackgroundHint(tvBackgroundHint)
        }

        btnToggle.setOnClickListener {
            if (overlay.isShowing()) {
                overlay.hide()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                overlay.show()
            }
            syncStates()
        }

        // Escape hatch for a list that piled up while the overlay was hidden.
        btnToggle.setOnLongClickListener {
            if (MsgStore.size() == 0) return@setOnLongClickListener false
            MsgStore.clear()
            Toast.makeText(this, "已清空待处理列表", Toast.LENGTH_SHORT).show()
            true
        }

        // 第四步：自启动 + 省电无限制。两项设置在不同页面，用一个按钮弹出入口，
        // 避免用户自己在层层的系统设置里翻。
        btnAutostart.setOnClickListener { showKeepAliveOptions() }

        findViewById<Button>(R.id.btnClear).setOnClickListener {            val n = MsgStore.size()
            if (n == 0) {
                Toast.makeText(this, "列表已经是空的", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Also clear the shade, otherwise the list refills on next refresh.
            val listener = NotificationListenerHolder.instance
            MsgStore.all().forEach { item ->
                listener?.dismissByKey(item.sbnKey, item.key)
            }
            MsgStore.clear()
            Toast.makeText(this, "已清空 $n 条", Toast.LENGTH_SHORT).show()
            syncStates()
        }

        setupAppearance()

        // 全生命周期注册：用户可能停留在设置页里改权限，此时 App 在后台，
        // 回调到达时直接刷新 UI，返回时状态就已经是对的。
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayOpsListener)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_notification_listeners"),
            false,
            notifyObserver
        )
    }

    override fun onDestroy() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.stopWatchingMode(overlayOpsListener)
        contentResolver.unregisterContentObserver(notifyObserver)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -- overlay appearance -------------------------------------------------

    private lateinit var rowBgPresets: LinearLayout
    private lateinit var rowFgPresets: LinearLayout
    private lateinit var tvBgValue: TextView
    private lateinit var tvFgValue: TextView
    private lateinit var tvFontLabel: TextView
    private lateinit var tvWidthLabel: TextView
    private lateinit var tvOpacityLabel: TextView
    private lateinit var tvAppearanceWarn: TextView
    private lateinit var sbFont: SeekBar
    private lateinit var sbWidth: SeekBar
    private lateinit var sbOpacity: SeekBar

    /** Preset swatches offered next to the RGB picker. */
    private val bgPresets = listOf(
        "白" to "#FFFFFF", "米" to "#F5F4F1", "浅灰" to "#E9E8E4",
        "浅蓝" to "#EAF1FC", "深灰" to "#2C2C2A", "黑" to "#141413",
    )
    private val fgPresets = listOf(
        "黑" to "#2C2C2A", "白" to "#FFFFFF", "深灰" to "#4A4945",
        "蓝" to "#1F4FA8", "红" to "#B3372F", "绿" to "#1E7A4B",
    )

    private fun setupAppearance() {
        rowBgPresets = findViewById(R.id.rowBgPresets)
        rowFgPresets = findViewById(R.id.rowFgPresets)
        tvBgValue = findViewById(R.id.tvBgValue)
        tvFgValue = findViewById(R.id.tvFgValue)
        tvFontLabel = findViewById(R.id.tvFontLabel)
        tvWidthLabel = findViewById(R.id.tvWidthLabel)
        tvOpacityLabel = findViewById(R.id.tvOpacityLabel)
        tvAppearanceWarn = findViewById(R.id.tvAppearanceWarn)
        sbFont = findViewById(R.id.sbFont)
        sbWidth = findViewById(R.id.sbWidth)
        sbOpacity = findViewById(R.id.sbOpacity)

        sbFont.max = Prefs.MAX_FONT - Prefs.MIN_FONT
        sbWidth.max = Prefs.MAX_WIDTH_DP - Prefs.MIN_WIDTH_DP
        // 1..100 maps to progress 0..99 so every step is a real percentage.
        sbOpacity.max = Prefs.MAX_OPACITY - Prefs.MIN_OPACITY

        buildSwatches(rowBgPresets, bgPresets) { applyBg(it) }
        buildSwatches(rowFgPresets, fgPresets) { applyFg(it) }

        findViewById<Button>(R.id.btnBgPick).setOnClickListener {
            pickRgb("选择背景色", prefs.overlayBg) { applyBg(it) }
        }
        findViewById<Button>(R.id.btnFgPick).setOnClickListener {
            pickRgb("选择文字色", prefs.overlayFg) { applyFg(it) }
        }

        sbFont.progress = prefs.overlayFontSize - Prefs.MIN_FONT
        sbWidth.progress = prefs.overlayWidth - Prefs.MIN_WIDTH_DP
        sbFont.setOnSeekBarChangeListener(simpleSeek { v ->
            tvFontLabel.text = "字号 ${v}sp"
            prefs.overlayFontSize = v
            onAppearanceChanged()
        })
        sbWidth.setOnSeekBarChangeListener(simpleSeek { v ->
            tvWidthLabel.text = "宽度 ${v}dp"
            prefs.overlayWidth = v
            onAppearanceChanged()
        })

        // Opacity updates the live window alpha only — no rebuild, so dragging
        // stays smooth instead of flickering the whole tree each frame.
        sbOpacity.progress = prefs.overlayOpacity - Prefs.MIN_OPACITY
        sbOpacity.setOnSeekBarChangeListener(simpleSeek { v ->
            tvOpacityLabel.text = "不透明度 $v%"
            prefs.overlayOpacity = v
            overlay.applyOpacity()
        })

        findViewById<Button>(R.id.btnSwapColors).setOnClickListener {
            val bg = prefs.overlayBg
            prefs.overlayBg = prefs.overlayFg
            prefs.overlayFg = bg
            refreshAppearanceUi()
            onAppearanceChanged()
        }
        findViewById<Button>(R.id.btnResetAppearance).setOnClickListener {
            prefs.overlayBg = Prefs.DEFAULT_BG
            prefs.overlayFg = Prefs.DEFAULT_FG
            prefs.overlayFontSize = Prefs.DEFAULT_FONT_SIZE
            prefs.overlayWidth = Prefs.DEFAULT_WIDTH_DP
            sbFont.progress = Prefs.DEFAULT_FONT_SIZE - Prefs.MIN_FONT
            sbWidth.progress = Prefs.DEFAULT_WIDTH_DP - Prefs.MIN_WIDTH_DP
            refreshAppearanceUi()
            onAppearanceChanged()
        }

        refreshAppearanceUi()
    }

    private fun applyBg(hex: String) {
        prefs.overlayBg = hex
        refreshAppearanceUi()
        onAppearanceChanged()
    }

    private fun applyFg(hex: String) {
        prefs.overlayFg = hex
        refreshAppearanceUi()
        onAppearanceChanged()
    }

    /** The overlay rebuilds itself entirely, since most colours depend on the pair. */
    private fun onAppearanceChanged() {
        if (overlay.isShowing()) overlay.applyAppearance()
        refreshAppearanceUi()
    }

    private fun refreshAppearanceUi() {
        tvBgValue.text = prefs.overlayBg.uppercase()
        tvFgValue.text = prefs.overlayFg.uppercase()
        tvFontLabel.text = "字号 ${prefs.overlayFontSize}sp"
        tvWidthLabel.text = "宽度 ${prefs.overlayWidth}dp"
        highlightSwatches(rowBgPresets, prefs.overlayBg)
        highlightSwatches(rowFgPresets, prefs.overlayFg)

        val warn = OverlayPalette.readabilityWarning(prefs.overlayBg, prefs.overlayFg)
        tvAppearanceWarn.visibility = if (warn == null) View.GONE else View.VISIBLE
        tvAppearanceWarn.text = warn?.let { "⚠ $it，建议换一组。" } ?: ""
    }

    private fun buildSwatches(
        host: LinearLayout,
        presets: List<Pair<String, String>>,
        onPick: (String) -> Unit,
    ) {
        host.removeAllViews()
        presets.forEach { (_, hex) ->
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also {
                    it.marginEnd = dp(8)
                }
                setOnClickListener { onPick(hex) }
                contentDescription = hex
            }
            host.addView(v)
        }
    }

    private fun highlightSwatches(host: LinearLayout, selected: String) {
        val presets = if (host === rowBgPresets) bgPresets else fgPresets
        for (i in 0 until host.childCount) {
            val hex = presets.getOrNull(i)?.second ?: continue
            val isSel = hex.equals(selected, ignoreCase = true)
            host.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor(hex))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(if (isSel) 2 else 1), Color.parseColor(if (isSel) "#2C2C2A" else "#E4E2DD"))
            }
        }
    }

    /**
     * Every slider stores `value - MIN` as its progress, so the base value has
     * to be looked up per bar. It used to be `if (sb === sbFont) MIN_FONT else
     * MIN_WIDTH_DP`, which silently added 180 to the opacity slider.
     */
    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val base = when (sb) {
                sbFont -> Prefs.MIN_FONT
                sbWidth -> Prefs.MIN_WIDTH_DP
                sbOpacity -> Prefs.MIN_OPACITY
                else -> 0
            }
            onChange(progress + base)
        }

        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
    }

    /**
     * A minimal RGB dialog: three sliders plus a live preview. Deliberately not
     * a hue wheel - three numbers are easier to reason about when matching an
     * existing theme, and it keeps the dialog small on a phone.
     */
    private fun pickRgb(title: String, initial: String, onPicked: (String) -> Unit) {
        val start = OverlayPalette.parse(initial, "#FFFFFF")
        val sliders = IntArray(3)
        val labels = arrayOf("R", "G", "B")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
            )
        }
        box.addView(preview)

        val readout = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
            textSize = 13f
            setTextColor(Color.parseColor("#5F5E5A"))
        }
        box.addView(readout)

        fun current(): Int = Color.rgb(sliders[0], sliders[1], sliders[2])

        fun repaint() {
            val c = current()
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(c)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.parseColor("#E4E2DD"))
            }
            readout.text = OverlayPalette.toHex(c)
        }

        val initialValues = intArrayOf(
            OverlayPalette.red(start), OverlayPalette.green(start), OverlayPalette.blue(start)
        )
        for (i in 0..2) {
            val label = TextView(this).apply {
                text = labels[i]
                textSize = 12f
                setTextColor(Color.parseColor("#5F5E5A"))
            }
            box.addView(label)
            val bar = SeekBar(this).apply { max = 255; progress = initialValues[i] }
            sliders[i] = initialValues[i]
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    sliders[i] = p
                    repaint()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
            box.addView(bar)
        }
        repaint()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onPicked(OverlayPalette.toHex(current())) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onResume() {
        super.onResume()
        requestPostNotificationsOnce()
        MsgStore.addListener(syncStatesListener)

        val notifyOn = notificationAccessGranted()
        if (notifyOn) {
            MsgNotificationListener.rebind(this)
            runCatching {
                ContextCompat.startForegroundService(
                    this, Intent(this, KeepAliveService::class.java)
                )
            }
        }
        if (notifyOn && Settings.canDrawOverlays(this) && prefs.overlayShown && !overlay.isShowing()) {
            overlay.show()
        }
        syncStates()
        // 兜底刷新：部分 ROM 的权限写入是异步的，且回调可能缺失。
        // 在三个时间点各刷一次，把「明明开了却显示未开启」的窗口压到最短。
        // 挂在 mainHandler 上而不是 decorView 上，这样 onDestroy 的
        // removeCallbacksAndMessages 能一并取消，页面关掉后不会再回调。
        listOf(300L, 1200L, 3000L).forEach { delay ->
            mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) syncStates() }, delay)
        }
    }

    override fun onPause() {
        MsgStore.removeListener(syncStatesListener)
        super.onPause()
    }

    private val syncStatesListener: () -> Unit = { syncStates() }

    /** Reflects every step's real state on its own card. */
    private fun syncStates() {
        val notifyOn = notificationAccessGranted()
        val overlayOn = Settings.canDrawOverlays(this)
        val keyOn = prefs.apiKey.isNotBlank()
        val batteryOn = batteryUnrestricted()

        chip(tvNotifyStatus, notifyOn, "已开启", "未开启")
        chip(tvOverlayStatus, overlayOn, "已开启", "未开启")
        chip(tvKeyStatus, keyOn, "已填写", "未填写")
        // 自启动查不到，只能反映省电策略，文案避免让人以为「已设置」= 自启动也开了。
        chip(tvBatteryStatus, batteryOn, "省电已无限制", "未设置")

        // Once a step is done its button has no job left, so take it away.
        btnNotifyPerm.visibility = if (notifyOn) View.GONE else View.VISIBLE
        btnOverlayPerm.visibility = if (overlayOn) View.GONE else View.VISIBLE
        // 这一项包含自启动，无法确认真实状态，所以按钮一直保留供用户复查。
        btnAutostart.text = if (batteryOn) "检查自启动" else "去开启"

        btnToggle.text = if (overlay.isShowing()) "隐藏悬浮窗" else "显示悬浮窗"
        btnToggle.isEnabled = notifyOn && overlayOn

        // 前三项是硬门槛；省电策略属于「稳定性」而非「能不能用」，
        // 所以不把它算进「还差几步」，只单独提示，避免用户以为用不了。
        val done = listOf(notifyOn, overlayOn, keyOn).count { it }
        statusDot.background = dot(if (done == 3) "#639922" else "#E24B4A")
        tvStatusSummary.text = when {
            done < 3 -> "还差 ${3 - done} 步就能用了"
            !batteryOn -> "基本就绪 —— 建议再设一下自启动 + 省电无限制，否则后台可能被冻结"
            else -> "全部就绪，正在为你判断消息"
        }

        // Live queue readout, so the user can see the app is actually working
        // even while the overlay is hidden.
        val queue = MsgStore.size()
        val urgent = MsgStore.countNow()
        findViewById<TextView>(R.id.tvQueueCount).text = when {
            queue == 0 -> "当前没有待处理的消息"
            urgent > 0 -> "当前 $queue 条待处理，其中 $urgent 条要马上回"
            else -> "当前 $queue 条待处理"
        }

        // "Nothing arrives" has two very different causes and they need
        // different fixes: the listener is not bound at all (re-grant
        // notification access / reboot), or it is bound and the chat app simply
        // is not posting. Showing the real bind state tells them which one it
        // is without digging through system settings.
        val bound = NotificationListenerHolder.instance != null
        tvListenerStatus.text = if (bound) {
            "监听服务：已连接"
        } else {
            "监听服务：未连接 —— 通常几秒内自动恢复；持续未恢复请重新打开「通知使用权」，或重启手机"
        }
        tvListenerStatus.setTextColor(Color.parseColor(if (bound) "#3B6D11" else "#A32D2D"))
    }

    /**
     * 「自启动 + 省电无限制」的两个入口。
     *
     * 各家 ROM 的设置页入口都不一样，而且**没有公开 API 能读取或改写**
     * （小米的自启动属于私有权限）。所以这里只做「把你送到正确的那一页」，
     * 具体开关由用户自己确认——任何声称能自动开启的方案都是骗人的。
     *
     * 依次尝试：厂商自启动管理页 → 应用详情页（兜底，多数机器上能一站配完），
     * 电池优化白名单则用 Android 官方的 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
     */
    private fun showKeepAliveOptions() {
        val names = arrayOf("打开「自启动」设置", "关闭电池优化（省电无限制）", "打开本应用详情页")
        AlertDialog.Builder(this)
            .setTitle("自启动 + 省电无限制")
            .setItems(names) { _, which ->
                when (which) {
                    0 -> openVendorAutostart()
                    1 -> openBatteryOptimization()
                    2 -> openAppDetails()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 优先跳到厂商的自启动管理页；跳不过去就退到应用详情页。 */
    private fun openVendorAutostart() {
        // 常见国产 ROM 的自启动管理页。逐个 try，第一个能打开的就用。
        val candidates = listOf(
            // 小米 / HyperOS
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // 华为 / 荣耀
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // OPPO / 一加
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            // vivo
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
        )
        for (c in candidates) {
            val ok = runCatching {
                startActivity(Intent().setComponent(c))
                true
            }.getOrDefault(false)
            if (ok) return
        }
        // 都不行（AOSP、三星等本来就没有自启动管理）→ 应用详情页
        openAppDetails()
        Toast.makeText(this, "这台机器没有单独的自启动页，请在本页确认省电策略", Toast.LENGTH_LONG).show()
    }

    /**
     * 电池优化白名单。这是唯一有官方 API 的一项，用系统对话框请求豁免，
     * 用户点一下「允许」即可，不用自己去翻设置。
     */
    @SuppressLint("BatteryLife")
    private fun openBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            Toast.makeText(this, "已经是「无限制」，无需再设", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        val ok = runCatching { startActivity(intent) }.isSuccess
        if (!ok) {
            // 少数 ROM 移除了这个 action，退到电池优化列表。
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                openAppDetails()
            }
        }
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "打不开系统设置，请手动前往", Toast.LENGTH_LONG).show() }
    }

    /**
     * 电池优化是否已豁免。
     *
     * 这是这一步唯一能查到的真实状态：自启动是厂商私有设置，没有任何公开
     * API 可读（这也是我们不能替用户打开它的原因）。所以徽章反映的是
     * 「省电无限制」，自启动只能靠用户自己确认——文案里写清楚了。
     */
    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true

    private fun chip(view: TextView, ok: Boolean, okText: String, todoText: String) {
        view.text = if (ok) okText else todoText
        view.setTextColor(Color.parseColor(if (ok) "#3B6D11" else "#A32D2D"))
        view.setBackgroundResource(
            if (ok) R.drawable.bg_chip_done else R.drawable.bg_chip_todo
        )
    }

    private fun dot(hex: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(hex))
    }

    private fun showTest(message: String, ok: Boolean) {
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.text = message
        tvTestResult.setTextColor(Color.parseColor(if (ok) "#3B6D11" else "#A32D2D"))
    }

    /**
     * The switch changes what "监听" means, so the line under it has to spell
     * out the active mode — the label alone cannot.
     */
    private fun refreshBackgroundHint(tv: TextView) {
        tv.text = if (prefs.backgroundOnly) {
            "已开启：只监听后台消息。停在微信里收到的消息不再进列表。"
        } else {
            "已关闭（默认）：不管停在微信里还是切到后台，都照常监听。"
        }
    }

    private fun requestPostNotificationsOnce() {
        if (askedPostNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        askedPostNotifications = true
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPostNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(':').any { it.contains(packageName) }
    }
}

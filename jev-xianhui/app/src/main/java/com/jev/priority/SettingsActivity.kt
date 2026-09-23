package com.jev.priority

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.jev.priority.core.MsgStore
import com.jev.priority.core.Prefs
import com.jev.priority.jev.PriorityClient
import com.jev.priority.notify.NotificationListenerHolder
import com.jev.priority.overlay.OverlayHolder
import com.jev.priority.overlay.OverlayPalette
import com.jev.priority.overlay.PriorityOverlay
import com.jev.priority.update.UpdateChecker
import kotlin.concurrent.thread

/**
 * 设置页：API Key、运行开关、悬浮窗外观。
 *
 * 这些东西原先都堆在主页，导致主页很长、每次都要滑好几屏。拆出来之后
 * 主页只剩四步权限引导 + 两个常用按钮，一屏看完。
 *
 * 悬浮窗是单例（[OverlayHolder]），所以这里改外观能立刻作用到屏幕上那个
 * 悬浮窗，和主页改是完全一样的。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: PriorityOverlay

    private lateinit var etKey: EditText
    private lateinit var tvKeyStatus: TextView
    private lateinit var tvTestResult: TextView
    private lateinit var tvListenerStatus: TextView
    private lateinit var tvNotifyStats: TextView
    private lateinit var tvQueueCount: TextView
    private lateinit var tvBackgroundHint: TextView

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

    private val bgPresets = listOf(
        "白" to "#FFFFFF", "米" to "#F5F4F1", "浅灰" to "#E9E8E4",
        "浅蓝" to "#EAF1FC", "深灰" to "#2C2C2A", "黑" to "#141413",
    )
    private val fgPresets = listOf(
        "黑" to "#2C2C2A", "白" to "#FFFFFF", "深灰" to "#4A4945",
        "蓝" to "#1F4FA8", "红" to "#B3372F", "绿" to "#1E7A4B",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        overlay = OverlayHolder.get(this)

        etKey = findViewById(R.id.etApiKey)
        tvKeyStatus = findViewById(R.id.tvKeyStatus)
        tvTestResult = findViewById(R.id.tvTestResult)
        tvListenerStatus = findViewById(R.id.tvListenerStatus)
        tvNotifyStats = findViewById(R.id.tvNotifyStats)
        tvQueueCount = findViewById(R.id.tvQueueCount)
        tvBackgroundHint = findViewById(R.id.tvBackgroundHint)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        etKey.setText(prefs.apiKey)

        runCatching {
            findViewById<TextView>(R.id.tvVersion).text =
                "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        }

        // -- API Key --
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val typed = etKey.text.toString().trim()
            prefs.apiKey = typed
            tvTestResult.visibility = View.GONE
            Toast.makeText(this, if (typed.isBlank()) "已清空 Key" else "已保存", Toast.LENGTH_SHORT).show()
            refreshRuntime()
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
                    refreshRuntime()
                }
            }
        }

        // -- 运行开关 --
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

        val cbBackgroundOnly = findViewById<CheckBox>(R.id.cbBackgroundOnly)
        cbBackgroundOnly.isChecked = prefs.backgroundOnly
        refreshBackgroundHint()
        cbBackgroundOnly.setOnCheckedChangeListener { _, v ->
            prefs.backgroundOnly = v
            refreshBackgroundHint()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            val n = MsgStore.size()
            if (n == 0) {
                Toast.makeText(this, "列表已经是空的", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val listener = NotificationListenerHolder.instance
            MsgStore.all().forEach { item -> listener?.dismissByKey(item.sbnKey, item.key) }
            MsgStore.clear()
            Toast.makeText(this, "已清空 $n 条", Toast.LENGTH_SHORT).show()
            refreshRuntime()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdate() }

        setupAppearance()
    }

    override fun onResume() {
        super.onResume()
        MsgStore.addListener(refreshListener)
        refreshRuntime()
        // 用户可能刚去「安装未知应用」权限页开了权限，回来直接把安装界面续上。
        resumePendingInstall()
    }

    override fun onPause() {
        MsgStore.removeListener(refreshListener)
        super.onPause()
    }

    private val refreshListener: () -> Unit = { refreshRuntime() }

    /** 运行状态、队列计数、Key 状态。 */
    private fun refreshRuntime() {
        val keyOn = prefs.apiKey.isNotBlank()
        chip(tvKeyStatus, keyOn, "已填写", "未填写")

        val bound = NotificationListenerHolder.instance != null
        tvListenerStatus.text = if (bound) "监听服务：已连接" else "监听服务：未连接（通常几秒内自动恢复）"
        tvListenerStatus.setTextColor(Color.parseColor(if (bound) "#3B6D11" else "#A32D2D"))
        tvNotifyStats.text = "本次收到通知 ${NotificationListenerHolder.seenCount} 条，" +
            "进入列表 ${NotificationListenerHolder.acceptedCount} 条，" +
            "移出 ${NotificationListenerHolder.droppedCount} 条"

        val queue = MsgStore.size()
        val urgent = MsgStore.countNow()
        tvQueueCount.text = when {
            queue == 0 -> "当前没有待处理的消息"
            urgent > 0 -> "当前 $queue 条待处理，$urgent 条要马上回"
            else -> "当前 $queue 条待处理"
        }
    }

    private fun refreshBackgroundHint() {
        tvBackgroundHint.text = if (prefs.backgroundOnly) {
            "只监听后台消息；停在微信里收到的消息不进列表。"
        } else {
            "停在微信里或切到后台，都照常监听。"
        }
    }

    private fun chip(view: TextView, ok: Boolean, okText: String, todoText: String) {
        view.text = if (ok) okText else todoText
        view.setTextColor(Color.parseColor(if (ok) "#3B6D11" else "#A32D2D"))
        view.setBackgroundResource(if (ok) R.drawable.bg_chip_done else R.drawable.bg_chip_todo)
    }

    private fun showTest(message: String, ok: Boolean) {
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.text = message
        tvTestResult.setTextColor(Color.parseColor(if (ok) "#3B6D11" else "#A32D2D"))
    }

    // -- 悬浮窗外观 ----------------------------------------------------------

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

    private fun onAppearanceChanged() {
        if (overlay.isShowing()) overlay.applyAppearance()
        refreshAppearanceUi()
    }

    private fun refreshAppearanceUi() {
        tvBgValue.text = prefs.overlayBg.uppercase()
        tvFgValue.text = prefs.overlayFg.uppercase()
        tvFontLabel.text = "字号 ${prefs.overlayFontSize}sp"
        tvWidthLabel.text = "宽度 ${prefs.overlayWidth}dp"
        tvOpacityLabel.text = "不透明度 ${prefs.overlayOpacity}%"
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
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginEnd = dp(6) }
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
                cornerRadius = dp(7).toFloat()
                setStroke(dp(if (isSel) 2 else 1), Color.parseColor(if (isSel) "#2C2C2A" else "#E4E2DD"))
            }
        }
    }

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

    private fun pickRgb(title: String, initial: String, onPicked: (String) -> Unit) {
        val start = OverlayPalette.parse(initial, "#FFFFFF")
        val sliders = IntArray(3)
        val labels = arrayOf("R", "G", "B")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
        }
        box.addView(preview)

        val readout = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
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
            box.addView(TextView(this).apply {
                text = labels[i]
                textSize = 12f
                setTextColor(Color.parseColor("#5F5E5A"))
            })
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

    // -- 在线更新 ------------------------------------------------------------

    private fun checkForUpdate() {
        val btn = findViewById<Button>(R.id.btnCheckUpdate)
        btn.isEnabled = false
        btn.text = "检查中…"
        thread(name = "update-check") {
            var failed = false
            val result = runCatching { UpdateChecker.check(this) }
                .onFailure { Log.w("JEVPRIORITY", "update check: ${it.message}") }
                .getOrElse { failed = true; null }
            val current = UpdateChecker.currentVersionName(this)
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "检查更新"
                when {
                    failed -> Toast.makeText(
                        this, "检查失败，请确认网络可用（GitHub 在国内可能不稳定）", Toast.LENGTH_LONG
                    ).show()

                    result != null -> confirmUpdate(result)
                    else -> AlertDialog.Builder(this)
                        .setTitle("已是最新版本")
                        .setMessage("当前 v$current")
                        .setPositiveButton("好", null)
                        .show()
                }
            }
        }
    }

    private fun confirmUpdate(info: UpdateChecker.Result) {
        val message = buildString {
            append("当前 v").append(info.currentTag.removePrefix("v"))
            append("，最新 ").append(info.latestTag).append("\n\n")
            if (info.notes.isNotBlank()) append(info.notes)
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ -> startDownload(info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startDownload(info: UpdateChecker.Result) {
        val dest = java.io.File(UpdateChecker.updateDir(this), "xianhui.apk")
        if (dest.exists()) dest.delete()

        val nm = getSystemService(NotificationManager::class.java)
        val channelId = "jev_update"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "版本更新", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notifyId = 2001

        fun showProgress(pct: Int) {
            val b = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载更新")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (pct < 0) b.setProgress(0, 0, true) else b.setProgress(100, pct, false)
            runCatching { nm.notify(notifyId, b.build()) }
        }

        Toast.makeText(this, "已开始下载，可在通知栏查看进度", Toast.LENGTH_SHORT).show()

        thread(name = "update-download") {
            val ok = runCatching {
                UpdateChecker.download(info.downloadUrl, dest) { pct -> showProgress(pct) }
                dest.length() > 0
            }.onFailure { Log.w("JEVPRIORITY", "update download failed: ${it.message}") }
                .getOrDefault(false)

            runOnUiThread {
                runCatching { nm.cancel(notifyId) }
                if (!ok) {
                    Toast.makeText(this, "下载失败，请稍后重试", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                UpdateChecker.install(this, dest)
                pendingApk = dest
            }
        }
    }

    private var pendingApk: java.io.File? = null

    private fun resumePendingInstall() {
        val apk = pendingApk ?: return
        if (!apk.exists()) {
            pendingApk = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) return
        pendingApk = null
        UpdateChecker.install(this, apk)
    }
}

package com.jev.priority

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
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jev.priority.core.MsgStore
import com.jev.priority.core.Prefs
import com.jev.priority.notify.KeepAliveService
import com.jev.priority.notify.MsgNotificationListener
import com.jev.priority.notify.NotificationListenerHolder
import com.jev.priority.overlay.OverlayHolder
import com.jev.priority.overlay.PriorityOverlay

/**
 * 主页：四步权限引导 + 运行状态 + 两个常用操作。
 *
 * 只负责「能不能用起来」这件事：通知使用权、悬浮窗、API Key、自启动。
 * API Key 的输入、运行开关、悬浮窗外观都在 [SettingsActivity]，所以这一屏
 * 一页就能看完，不必来回滑动。
 *
 * 每一步都是一张卡：徽章从红「未开启」变绿「已开启」，做完按钮自动消失，
 * 避免用户去点一个已经没有作用的按钮。两项特殊权限只能由用户在系统设置里
 * 授权，App 能检测状态、能把他送过去，但没法代开——这是系统设计。
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
    private lateinit var btnNotifyPerm: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnToggle: Button

    /**
     * Android 13+ 需要 POST_NOTIFICATIONS，否则保活的前台通知被隐藏，
     * ROM 就更有理由回收我们。每进程只问一次，避免被拒后陷入循环。
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
     * 只在 onResume 里查一次是不够的：从系统设置授权页返回的瞬间，ROM（小米 /
     * HyperOS 尤其明显）对 AppOps 的写入是异步的，立刻查询拿到的还是旧值 ——
     * 表现为「明明开了悬浮窗，卡片却显示未开启，退出重开才好」。所以：
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
        btnNotifyPerm = findViewById(R.id.btnNotifyPerm)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        btnToggle = findViewById(R.id.btnToggleOverlay)

        runCatching {
            findViewById<TextView>(R.id.tvVersion).text =
                "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        // 第三步的输入框在设置页，这里只把用户送过去。
        findViewById<Button>(R.id.btnKeyPerm).setOnClickListener { openSettings() }

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

        // 第四步：自启动 + 省电无限制。两项设置在不同页面，用一个按钮弹出入口，
        // 避免用户自己在层层的系统设置里翻。
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { showKeepAliveOptions() }

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

        // 长按清空：列表在悬浮窗隐藏时也会堆积，留一个不用打开悬浮窗的出口。
        btnToggle.setOnLongClickListener {
            if (MsgStore.size() == 0) return@setOnLongClickListener false
            MsgStore.clear()
            Toast.makeText(this, "已清空待处理列表", Toast.LENGTH_SHORT).show()
            true
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            val n = MsgStore.size()
            if (n == 0) {
                Toast.makeText(this, "列表已经是空的", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 同时清掉通知栏，否则下次刷新列表又会被填回来。
            val listener = NotificationListenerHolder.instance
            MsgStore.all().forEach { item -> listener?.dismissByKey(item.sbnKey, item.key) }
            MsgStore.clear()
            Toast.makeText(this, "已清空 $n 条", Toast.LENGTH_SHORT).show()
            syncStates()
        }

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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

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
        chip(findViewById(R.id.tvBatteryStatus), batteryOn, "省电已无限制", "未设置")

        // Once a step is done its button has no job left, so take it away.
        btnNotifyPerm.visibility = if (notifyOn) View.GONE else View.VISIBLE
        btnOverlayPerm.visibility = if (overlayOn) View.GONE else View.VISIBLE
        // 这一项包含自启动，无法确认真实状态，所以按钮一直保留供用户复查。
        findViewById<Button>(R.id.btnAutostart).text = if (batteryOn) "检查自启动" else "去开启"

        btnToggle.text = if (overlay.isShowing()) "隐藏悬浮窗" else "显示悬浮窗"
        btnToggle.isEnabled = notifyOn && overlayOn

        // 前三项是硬门槛；省电策略属于「稳定性」而非「能不能用」，
        // 所以不把它算进「还差几步」，只单独提示，避免用户以为用不了。
        val done = listOf(notifyOn, overlayOn, keyOn).count { it }
        statusDot.background = dot(if (done == 3) "#639922" else "#E24B4A")
        tvStatusSummary.text = when {
            done < 3 -> "还差 ${3 - done} 步就能用了"
            !batteryOn -> "就绪 —— 建议再设一下自启动 + 省电无限制"
            else -> "全部就绪"
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
            "监听服务：未连接（通常几秒内自动恢复）"
        }
        tvListenerStatus.setTextColor(Color.parseColor(if (bound) "#3B6D11" else "#A32D2D"))
    }

    /**
     * 「自启动 + 省电无限制」的两个入口。
     *
     * 各家 ROM 的设置页入口都不一样，而且**没有公开 API 能读取或改写**
     * （小米的自启动属于私有权限）。所以这里只做「把你送到正确的那一页」，
     * 具体开关由用户自己确认——任何声称能自动开启的方案都是骗人的。
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
    @android.annotation.SuppressLint("BatteryLife")
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
            }.onFailure { openAppDetails() }
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

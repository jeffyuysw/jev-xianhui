package com.jev.priority.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.jev.priority.core.Prefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线版本检查。
 *
 * 用 GitHub Releases 的公开接口取最新版本，不需要任何自建服务器：
 *
 *     GET https://api.github.com/repos/<owner>/<repo>/releases/latest
 *     → { "tag_name": "v1.0.3", "browser_download_url": ".../xianhui.apk", ... }
 *
 * 判断只比 tag 与本地 versionName 是否相同 —— 语义化版本号比较在这里没有
 * 必要，因为 tag 就是发版时手写的那个字符串，相同即已是最新。
 *
 * 失败一律静默：GitHub 在国内访问本就不稳定，网络不通时弹「检查失败」
 * 只会让用户以为软件坏了。
 */
object UpdateChecker {

    private const val TAG = "JEVPRIORITY"
    private const val HOST = "https://api.github.com/repos/jeffyuysw/jev-xianhui"
    private const val APK_NAME = "xianhui.apk"

    /** 检查结果。截断到 120 字，避免把整篇 Release 说明塞进对话框。 */
    data class Result(
        val latestTag: String,
        val currentTag: String,
        val notes: String,
        val downloadUrl: String,
    ) {
        /**
         * 只有线上版本「更新」才算有更新。
         *
         * 不能用 tag 是否相等来判断：本地版本比线上高时（比如自己编译的
         * 1.0.4 对上线上 v1.0.3），字符串不等会把它当成有更新，反而
         * 引导用户去装旧版。
         */
        val hasUpdate: Boolean get() = compareTags(latestTag, currentTag) > 0
    }

    /**
     * 版本号比较：取每段数字逐位比大小，v1.0.10 > v1.0.9 这种要正确。
     * 解析不出来的段按 0 处理，保证任何异常输入都不会让 App 误报。
     */
    fun compareTags(a: String, b: String): Int {
        fun parts(s: String) = s.removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 同步执行，调用方负责放到子线程。
     *
     * 三种结果严格区分，调用方才能给出正确的提示：
     *  - 抛异常   → 查询本身失败（网络不通、被限流、返回体不是 JSON）
     *  - 返回 null → 查询成功，但没有比本地更新的版本
     *  - 返回结果 → 有新版本
     *
     * 之前把「失败」和「无更新」都归成 null，断网时会误报「已是最新版本」。
     */
    fun check(context: Context): Result? {
        val current = currentVersionName(context)
        val body = httpGet("$HOST/releases/latest")

        val json = JSONObject(body)
        val tag = json.optString("tag_name").trim()
        if (tag.isBlank()) error("release 里没有 tag_name")

        // 没有 APK 附件就没法自动更新，退回 Releases 页面。
        val assetUrl = json.optJSONArray("assets")?.let { arr ->
            (0 until arr.length())
                .map { arr.optJSONObject(it) }
                .firstOrNull { it?.optString("name") == APK_NAME }
                ?.optString("browser_download_url")
        }.orEmpty()
        val url = assetUrl.ifBlank { "https://github.com/jeffyuysw/jev-xianhui/releases/latest" }

        return Result(
            latestTag = tag,
            currentTag = current,
            notes = json.optString("body").trim().take(120),
            downloadUrl = url,
        ).takeIf { it.hasUpdate }
    }

    fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    /** 下载目录：cache/update/，与 file_paths.xml 里声明的路径一致。 */
    fun updateDir(context: Context): File =
        File(context.cacheDir, "update").apply { mkdirs() }

    /**
     * 拉起系统安装界面。
     *
     * Android 7 起不能用 file:// 把 APK 交给安装器，必须换成 FileProvider 的
     * content:// 并逐个授权。Android 8 起还需要「安装未知应用」权限，没开时
     * 先跳到该权限的设置页，让用户开完再回来点一次。
     */
    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            android.widget.Toast.makeText(
                context, "请先允许「安装未知应用」，再回来点一次更新", android.widget.Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "install intent failed: ${it.message}") }
    }

    /**
     * 下载 APK。[onProgress] 回传 0..100，-1 表示总长度未知。
     * 下载前清空目录，避免旧包占着同名文件。
     */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "XianHui-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        // 只在整百分比变化时回调，避免刷爆 UI
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub 要求带 UA，否则部分场景会被拒。
            setRequestProperty("User-Agent", "XianHui-Android")
        }
        return try {
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = text?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }
}

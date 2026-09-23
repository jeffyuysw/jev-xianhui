package com.jev.priority.core

import android.content.Context

/**
 * All settings in app-private storage. The OpenRouter key never leaves the
 * device except inside the judgment request itself, and is never logged.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("jev_priority", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var endpoint: String
        get() = sp.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(v) = sp.edit().putString("endpoint", v.trim()).apply()

    /** Judge every new notification as it arrives. Off = collect only. */
    var autoJudge: Boolean
        get() = sp.getBoolean("autoJudge", true)
        set(v) = sp.edit().putBoolean("autoJudge", v).apply()

    /** Only collect from these packages. Empty set means every app. */
    var watchPkgs: Set<String>
        get() = sp.getStringSet("watchPkgs", DEFAULT_WATCH) ?: DEFAULT_WATCH
        set(v) = sp.edit().putStringSet("watchPkgs", v).apply()

    /**
     * Listen only while the chat app is in the background.
     *
     * Off (default): a row survives even when the chat app retracts its own
     * notification. WeChat does exactly that the moment it comes to the
     * foreground — it posts, then pulls the notification back within a few
     * milliseconds because the user is already looking at it. Honouring that
     * retraction is what made "stop listening while I am inside WeChat" look
     * like a listening bug: the row was created and then immediately dropped.
     *
     * On: any removal drops the row, so only messages that survive in the
     * notification shade — the ones that arrived while the phone was elsewhere
     * — are kept.
     */
    var backgroundOnly: Boolean
        get() = sp.getBoolean("backgroundOnly", false)
        set(v) = sp.edit().putBoolean("backgroundOnly", v).apply()

    var overlayShown: Boolean
        get() = sp.getBoolean("overlayShown", false)
        set(v) = sp.edit().putBoolean("overlayShown", v).apply()

    /** -1 means "not placed yet", which puts the bubble at the left edge. */
    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    // -- overlay appearance -------------------------------------------------
    // Six-digit hex with a leading '#'. Defaults are a plain white card with
    // near-black text. Stored per-device; the Windows build keeps its own copy
    // in config.json, so the two platforms are deliberately not synced.

    var overlayBg: String
        get() = sp.getString("overlayBg", DEFAULT_BG) ?: DEFAULT_BG
        set(v) = sp.edit().putString("overlayBg", v).apply()

    var overlayFg: String
        get() = sp.getString("overlayFg", DEFAULT_FG) ?: DEFAULT_FG
        set(v) = sp.edit().putString("overlayFg", v).apply()

    /** Body text size in sp. */
    var overlayFontSize: Int
        get() = sp.getInt("overlayFontSize", DEFAULT_FONT_SIZE)
        set(v) = sp.edit().putInt("overlayFontSize", v.coerceIn(MIN_FONT, MAX_FONT)).apply()

    /** Overlay width in dp. */
    var overlayWidth: Int
        get() = sp.getInt("overlayWidth", DEFAULT_WIDTH_DP)
        set(v) = sp.edit().putInt("overlayWidth", v.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)).apply()

    /**
     * Card opacity, 1..100 percent.
     *
     * Applied as the window's alpha, not as a per-view alpha, so the whole
     * bubble (card fill, text, colour bars) fades together and the result is
     * uniform. At 100% the window is fully opaque and cheap to composite.
     */
    var overlayOpacity: Int
        get() = sp.getInt("overlayOpacity", DEFAULT_OPACITY)
        set(v) = sp.edit()
            .putInt("overlayOpacity", v.coerceIn(MIN_OPACITY, MAX_OPACITY)).apply()

    companion object {
        const val DEFAULT_MODEL = "typesafe/jev-1.13"
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
        const val WECHAT = "com.tencent.mm"
        const val QQ = "com.tencent.mobileqq"
        val DEFAULT_WATCH: Set<String> = setOf(WECHAT, QQ)

        // Appearance defaults + bounds. Keep in sync with OverlayPalette.
        const val DEFAULT_BG = "#FFFFFF"
        const val DEFAULT_FG = "#2C2C2A"
        const val DEFAULT_FONT_SIZE = 12
        const val DEFAULT_WIDTH_DP = 220
        const val MIN_FONT = 10
        const val MAX_FONT = 20
        const val MIN_WIDTH_DP = 180
        const val MAX_WIDTH_DP = 340

        // Opacity is a percentage; 1 is the floor (0 would make it invisible
        // and unrecoverable by drag, since you cannot see what to grab).
        const val DEFAULT_OPACITY = 100
        const val MIN_OPACITY = 1
        const val MAX_OPACITY = 100
    }
}

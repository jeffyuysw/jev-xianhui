package com.jev.priority.overlay

import android.graphics.Color
import com.jev.priority.core.Bucket
import kotlin.math.abs
import kotlin.math.pow

/**
 * Every colour the overlay paints, derived from just the user's two choices
 * (background + text).
 *
 * The derivation mirrors `core/appearance.py` on Windows on purpose, so the two
 * builds look the same for the same pair of colours: the row tint, the hairline
 * border, the muted meta line and the accent shades are all computed rather
 * than stored. That keeps the settings to a single row instead of a dozen
 * sliders nobody wants to touch.
 *
 * When the background is dark the whole derivation flips: tints are made by
 * pulling toward the text colour (lighter), and the accents are lightened so
 * they stay visible against a near-black card.
 */
class OverlayPalette(bgHex: String, fgHex: String) {

    val bg: Int = parse(bgHex, DEFAULT_BG)
    val fg: Int = parse(fgHex, DEFAULT_FG)
    val dark: Boolean = luminance(bg) < 0.45

    /** Row fill — the background nudged very slightly toward the text colour. */
    val rowBg: Int = mix(bg, fg, if (dark) 0.10f else 0.06f)

    /** Hairline around the whole card. */
    val border: Int = mix(bg, fg, if (dark) 0.26f else 0.16f)

    /** The 1px rule above the footer. */
    val divider: Int = mix(bg, fg, if (dark) 0.18f else 0.10f)

    /** Secondary line (the "紧急 8/9 · 有时限" meta). */
    val textSoft: Int = mix(fg, bg, if (dark) 0.34f else 0.42f)

    /** Faintest line (empty-state hint). */
    val textFaint: Int = mix(fg, bg, if (dark) 0.52f else 0.62f)

    /** Text sitting on top of a saturated chip, picked for contrast. */
    fun on(color: Int): Int =
        if (contrastRatio(color, Color.WHITE) >= contrastRatio(color, Color.parseColor("#2C2C2A")))
            Color.WHITE else Color.parseColor("#2C2C2A")

    /** Accent for a bucket, lightened on a dark card so it stays legible. */
    fun accent(bucket: Bucket): Int {
        val base = BASE_ACCENT.getValue(bucket)
        return if (dark) mix(base, Color.WHITE, 0.30f) else base
    }

    /** Tinted chip behind a bucket's colour bar. */
    fun accentChip(bucket: Bucket): Int =
        mix(bg, accent(bucket), if (dark) 0.22f else 0.13f)

    companion object {
        const val DEFAULT_BG = "#FFFFFF"
        const val DEFAULT_FG = "#2C2C2A"

        val BASE_ACCENT: Map<Bucket, Int> = mapOf(
            Bucket.NOW to Color.parseColor("#D6453D"),
            Bucket.SOON to Color.parseColor("#D98A1F"),
            Bucket.LATER to Color.parseColor("#9B9A94"),
            Bucket.PENDING to Color.parseColor("#3B7DD8"),
        )

        // -- colour maths ---------------------------------------------------
        fun parse(hex: String?, fallback: String): Int =
            runCatching { Color.parseColor(hex?.trim().orEmpty()) }
                .getOrElse {
                    runCatching { Color.parseColor(fallback) }.getOrDefault(Color.WHITE)
                }

        fun red(c: Int) = (c shr 16) and 0xFF
        fun green(c: Int) = (c shr 8) and 0xFF
        fun blue(c: Int) = c and 0xFF

        /** WCAG relative luminance, 0 (black) .. 1 (white). */
        fun luminance(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
        }

        fun contrastRatio(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            val hi = maxOf(la, lb)
            val lo = minOf(la, lb)
            return (hi + 0.05) / (lo + 0.05)
        }

        /** Blend a -> b by t (0 = a, 1 = b). */
        fun mix(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            return Color.rgb(
                (red(a) + (red(b) - red(a)) * f).toInt(),
                (green(a) + (green(b) - green(a)) * f).toInt(),
                (blue(a) + (blue(b) - blue(a)) * f).toInt()
            )
        }

        fun toHex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

        /**
         * Flags unreadable combinations for the settings UI, without forbidding
         * them — the user is free to pick whatever they want.
         */
        fun readabilityWarning(bgHex: String, fgHex: String): String? {
            val ratio = contrastRatio(parse(bgHex, DEFAULT_BG), parse(fgHex, DEFAULT_FG))
            return when {
                ratio < 3.0 -> "对比度仅 ${"%.1f".format(ratio)}:1，文字几乎看不清"
                ratio < 4.5 -> "对比度偏低（${"%.1f".format(ratio)}:1），小字号下不好认"
                else -> null
            }
        }
    }
}

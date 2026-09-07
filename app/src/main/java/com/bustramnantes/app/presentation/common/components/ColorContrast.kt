package com.bustramnantes.app.presentation.common.components

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Parses a hex color string into a Compose [Color]. Accepts both the `"EE7402"` format used by
 * [com.bustramnantes.app.domain.model.Line]/[com.bustramnantes.app.domain.model.Favorite] and the
 * `"#EE7402"` format used by [com.bustramnantes.app.domain.model.Departure],
 * plus an optional leading alpha channel (`"AARRGGBB"`). Returns `null` on malformed input rather
 * than throwing, so callers can fall back to a theme color.
 */
fun parseHexColor(hex: String): Color? {
    val cleaned = hex.removePrefix("#").trim()
    val argb = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    return try {
        Color(argb.toLong(16))
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * WCAG relative luminance of [color] (0.0 = black, 1.0 = white).
 * https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 */
private fun relativeLuminance(color: Color): Double {
    fun channel(component: Float): Double {
        val c = component.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/**
 * WCAG contrast ratio between two colors, in `[1.0, 21.0]`.
 * https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio
 */
fun contrastRatio(colorA: Color, colorB: Color): Double {
    val l1 = relativeLuminance(colorA) + 0.05
    val l2 = relativeLuminance(colorB) + 0.05
    return if (l1 > l2) l1 / l2 else l2 / l1
}

/** WCAG AA minimum contrast ratio for normal-size text. */
const val WCAG_AA_NORMAL_TEXT_MIN_CONTRAST = 4.5

/**
 * Returns [foreground] unchanged if it already meets [minContrast] against [background]
 * ("all badge color pairs pass WCAG AA contrast ratio"). Otherwise falls back to
 * whichever of pure black/white has the higher contrast against [background], guaranteeing a
 * readable result even when the API-supplied color pair is broken.
 */
fun ensureAccessibleTextColor(
    background: Color,
    foreground: Color,
    minContrast: Double = WCAG_AA_NORMAL_TEXT_MIN_CONTRAST,
): Color {
    if (contrastRatio(background, foreground) >= minContrast) return foreground
    val whiteRatio = contrastRatio(background, Color.White)
    val blackRatio = contrastRatio(background, Color.Black)
    return if (whiteRatio >= blackRatio) Color.White else Color.Black
}

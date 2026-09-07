package com.bustramnantes.app.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.TransportMode

/** Visual size variants for [LineBadge]. */
enum class LineBadgeSize {
    Small,
    Medium,
    Large,
}

private data class LineBadgeMetrics(val minSize: Dp, val fontSize: TextUnit)

private fun metricsFor(size: LineBadgeSize): LineBadgeMetrics = when (size) {
    LineBadgeSize.Small -> LineBadgeMetrics(20.dp, 11.sp)
    LineBadgeSize.Medium -> LineBadgeMetrics(28.dp, 13.sp)
    LineBadgeSize.Large -> LineBadgeMetrics(36.dp, 16.sp)
}

/**
 * Line badge: a circular pill for [TransportMode.BUS], a taller rounded-rectangle
 * pill for [TransportMode.TRAM] (matches physical Nantes signage), a boat hull for the boats
 * ([TransportMode.FERRY] Navibus, [TransportMode.BAC]). Background = line color,
 * label color = line text color, contrast-corrected to WCAG AA if the API-supplied pair fails
 * (see [ensureAccessibleTextColor]).
 *
 * This overload takes a domain [Line] (colors stored *without* a leading `#`).
 */
@Composable
fun LineBadge(
    line: Line,
    modifier: Modifier = Modifier,
    size: LineBadgeSize = LineBadgeSize.Medium,
) {
    LineBadge(
        shortName = line.shortName,
        backgroundColorHex = line.color,
        textColorHex = line.textColor,
        mode = line.mode,
        modifier = modifier,
        size = size,
    )
}

/**
 * Primitive [LineBadge] overload for callers holding loose fields rather than a [Line] instance
 * (e.g. [com.bustramnantes.app.domain.model.Departure], whose `lineColor`/`lineTextColor` are hex
 * strings *with* a leading `#`, or [com.bustramnantes.app.domain.model.Favorite]'s nullable
 * line fields once null-checked by the caller). Accepts hex with or without a leading `#`.
 */
@Composable
fun LineBadge(
    shortName: String,
    backgroundColorHex: String,
    textColorHex: String,
    mode: TransportMode,
    modifier: Modifier = Modifier,
    size: LineBadgeSize = LineBadgeSize.Medium,
) {
    val background = parseHexColor(backgroundColorHex) ?: MaterialTheme.colorScheme.primary
    val rawForeground = parseHexColor(textColorHex) ?: MaterialTheme.colorScheme.onPrimary
    val foreground = ensureAccessibleTextColor(background, rawForeground)
    val metrics = metricsFor(size)
    val shape = when (mode) {
        TransportMode.TRAM -> RoundedCornerShape(percent = 40)
        TransportMode.FERRY, TransportMode.BAC -> BoatHullShape
        TransportMode.BUS -> CircleShape
    }
    val horizontalPadding = when (mode) {
        TransportMode.TRAM -> 10.dp
        TransportMode.FERRY, TransportMode.BAC -> 6.dp
        TransportMode.BUS -> 4.dp
    }
    val isBoat = mode == TransportMode.FERRY || mode == TransportMode.BAC

    Box(
        modifier = modifier
            .height(metrics.minSize)
            .widthIn(min = if (isBoat) metrics.minSize * 1.65f else metrics.minSize)
            .clip(shape)
            .background(background)
            .padding(horizontal = horizontalPadding)
            // Keep the label in the hull's body: clear of the bow on the right, a touch below the deck.
            .padding(end = if (isBoat) metrics.minSize * 0.35f else 0.dp, top = if (isBoat) metrics.minSize * 0.08f else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = shortName,
            color = foreground,
            fontSize = metrics.fontSize,
            fontWeight = MaterialTheme.typography.labelLarge.fontWeight,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * A boat seen side-on: flat deck, stern on the left, a bow sweeping up to a point on the right,
 * flat keel - the badge of the boat lines (Navibus, bacs), next to the bus circle and the tram
 * rectangle. `res/drawable/badge_boat_hull.xml` is the same outline for the Glance widget.
 */
val BoatHullShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(0f, h * 0.10f)
    lineTo(w * 0.80f, h * 0.10f)
    cubicTo(w * 0.90f, h * 0.06f, w * 0.96f, h * 0.02f, w, 0f)
    cubicTo(w * 0.93f, h * 0.55f, w * 0.82f, h, w * 0.68f, h)
    lineTo(w * 0.10f, h)
    cubicTo(w * 0.05f, h, w * 0.02f, h * 0.60f, 0f, h * 0.10f)
    close()
}

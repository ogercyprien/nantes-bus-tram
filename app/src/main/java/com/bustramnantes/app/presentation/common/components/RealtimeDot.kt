package com.bustramnantes.app.presentation.common.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "temps réel" cue for a live departure: a small dot that gently bounces up and
 * down, so a real-time time reads as *live* at a glance rather than as a static bullet. The motion
 * is a single infinite [rememberInfiniteTransition] with an eased reverse cycle - subtle, not
 * distracting - and it fades slightly at the bottom of the travel for a bit of depth.
 *
 * Sized [dotSize] wide and `dotSize + travel` tall (the extra height is the vertical runway), and
 * vertically centered on the text baseline area by its callers via a fixed-width leading slot.
 */
@Composable
fun RealtimeDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 5.dp,
    travel: Dp = 5.dp,
) {
    val transition = rememberInfiniteTransition(label = "realtime")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "realtime-bounce",
    )

    Canvas(
        modifier = modifier.dotBounds(dotSize, travel),
    ) {
        val radius = dotSize.toPx() / 2f
        val travelPx = travel.toPx()
        // progress 0 -> resting at the bottom, 1 -> lifted to the top of the runway.
        val centerY = size.height - radius - progress * travelPx
        drawCircle(
            color = color.copy(alpha = 0.55f + 0.45f * progress),
            radius = radius,
            center = Offset(size.width / 2f, centerY),
        )
    }
}

/** Fixes the drawing box to [dotSize] × ([dotSize] + [travel]) regardless of incoming constraints. */
private fun Modifier.dotBounds(dotSize: Dp, travel: Dp): Modifier = layout { measurable, _ ->
    val w = dotSize.roundToPx()
    val h = (dotSize + travel).roundToPx()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    layout(w, h) { placeable.place(0, 0) }
}

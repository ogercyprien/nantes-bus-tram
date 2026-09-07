package com.bustramnantes.app.presentation.common.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Line

/**
 * A single-line strip of [LineBadge]s that never wraps and never grows past the width it is given.
 *
 * - When every badge fits, the strip is just a static row at its natural width (exactly what the
 *   rows showed before).
 * - When they don't fit, the strip is clipped to the available width and its content scrolls
 *   right-to-left in a continuous loop ([basicMarquee] with `iterations = Int.MAX_VALUE`) so every
 *   badge is eventually revealed - a stop like "Commerce" serves far more lines than a row can hold.
 *   [basicMarquee] measures its child unbounded and only animates when that child overflows, which
 *   is precisely the "static if it fits, scroll if not" behaviour wanted, with no manual measuring.
 *
 * [basicMarquee] is a pure layout/draw modifier: it installs no pointer-input handler, so taps on the
 * strip fall through to the enclosing row's `clickable` (unlike `horizontalScroll`, which would also
 * hijack horizontal drags).
 *
 * Usually hosted by [NameWithLineBadges], which decides how much width the strip gets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LineBadgeStrip(
    lines: List<Line>,
    modifier: Modifier = Modifier,
    size: LineBadgeSize = LineBadgeSize.Small,
) {
    Row(
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            // Continuous loop: no pause between laps, the [spacing] gap separates them visually.
            repeatDelayMillis = 0,
            // Let the user read the first badges (and the stop name) before anything moves.
            initialDelayMillis = MARQUEE_INITIAL_DELAY_MS,
            spacing = MarqueeSpacing(MARQUEE_LAP_SPACING),
            velocity = MARQUEE_VELOCITY,
        ),
        horizontalArrangement = Arrangement.spacedBy(BADGE_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lines.forEach { line -> LineBadge(line = line, size = size) }
    }
}

/**
 * Lays out a row title (typically a stop name) followed by a [LineBadgeStrip], giving the **title
 * priority** over the badges.
 *
 * A plain `Row` can't express this: with the title `weight(1f)` the unweighted badge row is measured
 * first and may eat the whole width (the "Commerce" bug - the name squeezed to nothing), and
 * `weight(1f, fill = false)` on the title still hands the badges first pick. So this is a custom
 * [Layout] that measures in priority order:
 *
 * 1. Reserve `min(badges' natural width, minBadgeWidth)` for the strip (so a stop with one badge
 *    doesn't reserve more than it needs, and a stop with many always keeps a visible strip).
 * 2. Measure the [title] with `maxWidth = total − reserved − spacing`; it takes only what it needs
 *    and ellipsizes (if the caller set `maxLines = 1` + ellipsis) only when the name alone is longer.
 * 3. Give the strip whatever width remains; it shows statically if it fits, marquees otherwise.
 *
 * The title is placed at the start, the strip at the end (matching the former `weight(1f)` look),
 * both vertically centred. With no [lines], the title gets the full width.
 */
@Composable
fun NameWithLineBadges(
    title: @Composable () -> Unit,
    lines: List<Line>,
    modifier: Modifier = Modifier,
    badgeSize: LineBadgeSize = LineBadgeSize.Small,
    minBadgeWidth: Dp = DEFAULT_MIN_BADGE_WIDTH,
    spacing: Dp = 12.dp,
) {
    Layout(
        modifier = modifier,
        content = {
            title()
            if (lines.isNotEmpty()) LineBadgeStrip(lines = lines, size = badgeSize)
        },
    ) { measurables, constraints ->
        val titleMeasurable = measurables[0]
        val stripMeasurable = measurables.getOrNull(1)
        // The row is normally hosted with a bounded width (fillMaxWidth / weight); fall back to the
        // natural widths if someone ever places it in an unbounded context.
        val totalWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            titleMeasurable.maxIntrinsicWidth(constraints.maxHeight) +
                (stripMeasurable?.maxIntrinsicWidth(constraints.maxHeight) ?: 0) +
                spacing.roundToPx()
        }
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        if (stripMeasurable == null) {
            val title = titleMeasurable.measure(loose.copy(maxWidth = totalWidth))
            val height = title.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            return@Layout layout(totalWidth.coerceAtLeast(constraints.minWidth), height) {
                title.placeRelative(0, (height - title.height) / 2)
            }
        }

        val gap = spacing.roundToPx()
        val reserved = minOf(
            stripMeasurable.maxIntrinsicWidth(constraints.maxHeight),
            minBadgeWidth.roundToPx(),
        ).coerceAtLeast(0)
        val titleMax = (totalWidth - reserved - gap).coerceAtLeast(0)
        val title = titleMeasurable.measure(loose.copy(maxWidth = titleMax))

        val stripMax = (totalWidth - title.width - gap).coerceAtLeast(0)
        val strip = stripMeasurable.measure(loose.copy(maxWidth = stripMax))

        val height = maxOf(title.height, strip.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight.coerceAtLeast(constraints.minHeight))
        val width = totalWidth.coerceIn(constraints.minWidth, maxOf(constraints.minWidth, totalWidth))
        layout(width, height) {
            title.placeRelative(0, (height - title.height) / 2)
            strip.placeRelative(width - strip.width, (height - strip.height) / 2)
        }
    }
}

private val BADGE_SPACING = 4.dp

/** Two Small bus badges plus their gap - always keep at least that much strip visible. */
private val DEFAULT_MIN_BADGE_WIDTH = 48.dp

private const val MARQUEE_INITIAL_DELAY_MS = 1_500
private val MARQUEE_LAP_SPACING = 24.dp
private val MARQUEE_VELOCITY = 30.dp


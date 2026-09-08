package com.bustramnantes.app.presentation.common.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.presentation.common.utils.END_OF_SERVICE_LABEL
import com.bustramnantes.app.presentation.common.utils.PHANTOM_DEPARTURE_LABEL
import com.bustramnantes.app.presentation.common.utils.formatDepartureTime
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Renders a single departure as `"3 min (17:43)"`, preceded by a single, unambiguous
 * real-time cue: a small colored dot that gently bounces up and down ([RealtimeDot]) means the time
 * is live (real-time), while a scheduled/theoretical time simply has no dot and is shown in a muted
 * color. The animation reads as "live" at a glance without needing a legend. A fixed-width leading
 * slot keeps the times left-aligned whether or not the dot is present, so a vertical list stays tidy.
 *
 * When [departureTime] is `null` (no more departures today) it renders the [END_OF_SERVICE_LABEL]
 * state instead.
 *
 * A [isSuspectedPhantom] departure (a scheduled time no vehicle seems to be running - see
 * [com.bustramnantes.app.domain.util.tagSuspectedPhantoms]) is kept visible but de-emphasised:
 * outline color, italic, the time struck through and followed by [PHANTOM_DEPARTURE_LABEL] (left
 * un-struck so it stays readable), so the eye goes to the next
 * confirmed departure the way the street screens do.
 */
@Composable
fun DepartureTimeText(
    departureTime: Instant?,
    isRealtime: Boolean,
    modifier: Modifier = Modifier,
    now: Instant = Clock.System.now(),
    isSuspectedPhantom: Boolean = false,
) {
    if (departureTime == null) {
        EndOfServiceText(modifier = modifier)
        return
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Fixed-width slot so scheduled (dot-less) and real-time rows share the same left margin.
        Box(
            modifier = Modifier
                .size(width = 8.dp, height = 14.dp)
                .then(if (isRealtime) Modifier.semantics { contentDescription = "Temps réel" } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (isRealtime) {
                RealtimeDot()
            }
        }
        Spacer(Modifier.width(6.dp))
        val time = formatDepartureTime(departureTime, now)
        Text(
            text = if (isSuspectedPhantom) {
                buildAnnotatedString {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(time) }
                    append(" · $PHANTOM_DEPARTURE_LABEL")
                }
            } else {
                AnnotatedString(time)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (isSuspectedPhantom) FontStyle.Italic else FontStyle.Normal,
            color = when {
                isSuspectedPhantom -> MaterialTheme.colorScheme.outline
                isRealtime -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = if (isSuspectedPhantom) {
                Modifier.semantics { contentDescription = "$time, passage $PHANTOM_DEPARTURE_LABEL" }
            } else {
                Modifier
            },
        )
    }
}

/** Convenience overload reading straight from a [Departure]. */
@Composable
fun DepartureTimeText(
    departure: Departure,
    modifier: Modifier = Modifier,
    now: Instant = Clock.System.now(),
) {
    DepartureTimeText(
        departureTime = departure.nextDepartedTime,
        isRealtime = departure.isRealtime,
        modifier = modifier,
        now = now,
        isSuspectedPhantom = departure.isSuspectedPhantom,
    )
}

/** Standalone `"Fin de service"` label, for callers not going through [DepartureTimeText]. */
@Composable
fun EndOfServiceText(modifier: Modifier = Modifier) {
    Text(
        text = END_OF_SERVICE_LABEL,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

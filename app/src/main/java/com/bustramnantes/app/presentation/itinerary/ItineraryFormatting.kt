package com.bustramnantes.app.presentation.itinerary

import com.bustramnantes.app.domain.model.JourneySection
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong

/** Formats an [Instant] as a bare `"HH:mm"` clock time in [zone] - used for journey/leg endpoints. */
fun formatClockTime(
    instant: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val local = instant.toLocalDateTime(zone)
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

/** Formats a duration in seconds as `"23 min"` or, past the hour mark, `"1 h 05"`. */
fun formatJourneyDuration(totalSeconds: Int): String {
    val totalMinutes = (totalSeconds / 60.0).roundToLong().coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h ${minutes.toString().padStart(2, '0')}" else "$minutes min"
}

/** `"Trajet direct"` / `"1 correspondance"` / `"N correspondances"` per [transferCount]. */
fun formatTransferCount(transferCount: Int): String = when (transferCount) {
    0 -> "Trajet direct"
    1 -> "1 correspondance"
    else -> "$transferCount correspondances"
}

/**
 * Total walking time of a journey, in whole minutes, as the rider would add it up from the detail
 * view: each [JourneySection.Walking] leg is rounded to the minute **individually** (exactly like
 * [formatWalkingSummary]'s `~N min`) and those per-leg minutes are summed. Summing raw seconds and
 * rounding once could disagree with the detail screen by a minute (e.g. 2 x 90 s shows "~2 min" twice
 * but would total "3 min").
 *
 * Pass the journey's sections as returned by the use case - they are already trimmed of the
 * redundant access/egress walks at picked stops ([com.bustramnantes.app.domain.usecase.trimAccessEgressWalks]),
 * so the total matches the legs actually displayed.
 */
fun totalWalkingMinutes(sections: List<JourneySection>): Long =
    sections.filterIsInstance<JourneySection.Walking>()
        .sumOf { (it.durationSeconds / 60.0).roundToLong().coerceAtLeast(0L) }

/**
 * `"Temps total de marche : 12 min"` (or `"... : 1 h 05"` past the hour, same format as
 * [formatJourneyDuration]), with a no-break space before the colon per French typography. Returns
 * `null` when there is no walking to speak of (no walk leg, or every leg rounds to 0 min) - the card
 * then simply omits the line rather than stating the obvious.
 */
fun formatTotalWalkingTime(sections: List<JourneySection>): String? {
    val minutes = totalWalkingMinutes(sections)
    if (minutes <= 0L) return null
    return "Temps total de marche\u00A0: ${formatJourneyDuration((minutes * 60).toInt())}"
}

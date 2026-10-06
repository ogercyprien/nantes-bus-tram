package com.bustramnantes.app.presentation.itinerary.share

import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.presentation.common.utils.PHANTOM_DEPARTURE_LABEL
import com.bustramnantes.app.presentation.itinerary.formatClockTime
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Pure (Android-free) text building for the itinerary share actions - the single source for the
 * "Partager en texte" body, the whole-trip calendar event's description and the per-leg calendar
 * events, so all three always describe the journey identically. JVM-tested by
 * `JourneyShareTextTest`.
 *
 * Everything works on the journey **as displayed**: [Journey.sections] has already been through
 * [com.bustramnantes.app.domain.usecase.trimAccessEgressWalks], so a trimmed access/egress walk
 * never reappears in a shared text.
 */

/**
 * One displayed leg flattened into the names/times a sentence needs.
 *
 * The domain [JourneySection.Walking] carries neither endpoint names nor clock times (only a
 * duration), so [toShareLegs] derives them: a walk goes from the previous ride's alighting stop (or
 * the trip origin) to the next ride's boarding stop (or the trip destination), and starts when the
 * previous leg ended - `start + duration` is its end, which leaves any wait for the next bus out of
 * the walk ("Marche … 15:08-15:10", then "Bus 12 … 15:19-…").
 */
sealed interface ShareLeg {
    val from: String
    val to: String
    val start: Instant
    val end: Instant

    data class Ride(
        /** `"Bus C2"` / `"Tram 1"` - see [transitLabel]. */
        val label: String,
        override val from: String,
        override val to: String,
        val direction: String,
        override val start: Instant,
        override val end: Instant,
        val isSuspectedPhantom: Boolean = false,
    ) : ShareLeg

    data class Walk(
        override val from: String,
        override val to: String,
        override val start: Instant,
        override val end: Instant,
    ) : ShareLeg
}

/**
 * The calendar-agnostic content of one event to insert (see `JourneyShareIntents.calendarInsertIntent`,
 * which turns it into an `ACTION_INSERT` intent). [location] is `null` when there is nothing useful.
 */
data class CalendarEventSpec(
    val title: String,
    val start: Instant,
    val end: Instant,
    val location: String?,
    val description: String?,
)

/**
 * `"Bus C2"` / `"Tram 1"` / `"Navibus N2"`, from the GTFS `route_type`: the Navibus boats (N1/N2/N3)
 * are type 4 ([TransportMode.FERRY]) - never guess from a leading "N", NA/NC/NN/NO/NS are night
 * *buses*. Busway lines read as "Bus", which they are.
 */
fun transitLabel(section: JourneySection.Transit): String {
    val mode = when (section.transportMode) {
        TransportMode.FERRY -> "Navibus"
        TransportMode.BAC -> "Bac"
        TransportMode.TRAM -> "Tram"
        TransportMode.BUS -> "Bus"
    }
    return "$mode ${section.lineNumber}".trim()
}

/**
 * Trip endpoints as the user typed/picked them in the search ([fromLabel]/[toLabel]: a stop name, a
 * geocoded address or the current-position label), falling back to the first boarding / last
 * alighting stop when a label is blank.
 */
private fun Journey.originName(fromLabel: String): String = fromLabel.ifBlank {
    sections.filterIsInstance<JourneySection.Transit>().firstOrNull()
        ?.concernedStops?.firstOrNull()?.name.orEmpty()
}

private fun Journey.destinationName(toLabel: String): String = toLabel.ifBlank {
    sections.filterIsInstance<JourneySection.Transit>().lastOrNull()
        ?.concernedStops?.lastOrNull()?.name.orEmpty()
}

/** `"Trajet Rond-Point de Rennes → Foch Cathédrale"`. */
fun journeyTitle(journey: Journey, fromLabel: String, toLabel: String): String =
    "Trajet ${joinEndpoints(journey.originName(fromLabel), journey.destinationName(toLabel))}".trimEnd()

/** Flattens [Journey.sections] into [ShareLeg]s (see [ShareLeg] for how walks get names/times). */
fun Journey.toShareLegs(fromLabel: String, toLabel: String): List<ShareLeg> {
    val origin = originName(fromLabel)
    val destination = destinationName(toLabel)
    var cursor = departureTime
    return sections.mapIndexed { index, section ->
        when (section) {
            is JourneySection.Transit -> {
                cursor = section.arrivalTime
                ShareLeg.Ride(
                    label = transitLabel(section),
                    from = section.concernedStops.firstOrNull()?.name.orEmpty(),
                    to = section.concernedStops.lastOrNull()?.name.orEmpty(),
                    direction = section.directionName,
                    start = section.departureTime,
                    end = section.arrivalTime,
                    isSuspectedPhantom = section.isSuspectedPhantom,
                )
            }

            is JourneySection.Walking -> {
                val previous = sections.getOrNull(index - 1) as? JourneySection.Transit
                val next = sections.getOrNull(index + 1) as? JourneySection.Transit
                val start = cursor
                val end = start + section.durationSeconds.seconds
                cursor = end
                ShareLeg.Walk(
                    from = previous?.concernedStops?.lastOrNull()?.name ?: if (index == 0) origin else "",
                    to = next?.concernedStops?.firstOrNull()?.name
                        ?: if (index == sections.lastIndex) destination else "",
                    start = start,
                    end = end,
                )
            }
        }
    }
}

/**
 * One sentence per leg, e.g.
 * `"Bus C2 Rond-Point de Rennes → Talensac (direction Gare Sud), 15:02-15:08"` or
 * `"Marche Talensac → Place du cirque, 15:08-15:10"`. A suspected phantom ride gets a trailing
 * `" - probablement supprimé"`, as everywhere else in the app.
 */
fun formatShareLeg(leg: ShareLeg, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val times = "${formatClockTime(leg.start, zone)}-${formatClockTime(leg.end, zone)}"
    val stops = joinEndpoints(leg.from, leg.to)
    return when (leg) {
        is ShareLeg.Ride -> buildString {
            append(leg.label)
            if (stops.isNotEmpty()) append(' ').append(stops)
            if (leg.direction.isNotBlank()) append(" (direction ").append(leg.direction).append(')')
            append(", ").append(times)
            if (leg.isSuspectedPhantom) append(" - ").append(PHANTOM_DEPARTURE_LABEL)
        }

        is ShareLeg.Walk -> buildString {
            append("Marche")
            if (stops.isNotEmpty()) append(' ').append(stops)
            append(", ").append(times)
        }
    }
}

/**
 * The "Partager en texte" body:
 * ```
 * Trajet Rond-Point de Rennes → Foch Cathédrale
 * - Bus C2 Rond-Point de Rennes → Talensac (direction Gare Sud), 15:02-15:08
 * - Marche Talensac → Place du cirque, 15:08-15:10
 * ```
 * When the trip does not start on [today] a `"Le samedi 11 octobre 2026"` line follows the title -
 * bare clock times would otherwise be read as today's.
 */
fun buildJourneyShareText(
    journey: Journey,
    fromLabel: String,
    toLabel: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate? = null,
): String = buildString {
    append(journeyTitle(journey, fromLabel, toLabel))
    val tripDate = journey.departureTime.toLocalDateTime(zone).date
    if (today != null && tripDate != today) {
        append('\n').append("Le ").append(formatFrenchDate(tripDate))
    }
    journey.toShareLegs(fromLabel, toLabel).forEach { leg ->
        append('\n').append("- ").append(formatShareLeg(leg, zone))
    }
}

/** The whole trip as ONE calendar event: title + one [formatShareLeg] line per leg as description. */
fun buildJourneyCalendarEvent(
    journey: Journey,
    fromLabel: String,
    toLabel: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): CalendarEventSpec {
    val legs = journey.toShareLegs(fromLabel, toLabel)
    return CalendarEventSpec(
        title = journeyTitle(journey, fromLabel, toLabel),
        start = journey.departureTime,
        end = journey.arrivalTime,
        location = journey.originName(fromLabel).ifBlank { null },
        description = legs.joinToString("\n") { formatShareLeg(it, zone) },
    )
}

/**
 * One calendar event for one ride: `"Bus C2 → Talensac"`, located at the boarding stop, spanning
 * departure → arrival, with the full leg sentence as description.
 */
fun buildLegCalendarEvent(
    leg: ShareLeg.Ride,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): CalendarEventSpec = CalendarEventSpec(
    title = if (leg.to.isNotBlank()) "${leg.label} → ${leg.to}" else leg.label,
    start = leg.start,
    end = leg.end,
    location = leg.from.ifBlank { null },
    description = formatShareLeg(leg, zone),
)

private fun joinEndpoints(from: String, to: String): String = when {
    from.isNotBlank() && to.isNotBlank() -> "$from → $to"
    else -> from.ifBlank { to }
}

private val FRENCH_DAYS = mapOf(
    DayOfWeek.MONDAY to "lundi",
    DayOfWeek.TUESDAY to "mardi",
    DayOfWeek.WEDNESDAY to "mercredi",
    DayOfWeek.THURSDAY to "jeudi",
    DayOfWeek.FRIDAY to "vendredi",
    DayOfWeek.SATURDAY to "samedi",
    DayOfWeek.SUNDAY to "dimanche",
)

private val FRENCH_MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** `"samedi 11 octobre 2026"` (`"1er"` for the first of the month, as French writes it). */
fun formatFrenchDate(date: LocalDate): String {
    val day = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
    return "${FRENCH_DAYS.getValue(date.dayOfWeek)} $day ${FRENCH_MONTHS[date.monthNumber - 1]} ${date.year}"
}

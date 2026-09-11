package com.bustramnantes.app.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * A theoretical (scheduled) timetable for one line+direction at a stop on a given service [date], from
 * the local GTFS network. Distinct from the live [Departure] board: this is the full published
 * schedule for a day, used by the "Horaires théoriques" surface.
 *
 * [entries] is flat and chronological (after-midnight runs sorted last - see [TimetableEntry.nextDay]).
 * An **empty** [entries] means nothing runs on [date] - either a genuinely empty service day or a
 * date outside the installed feed's validity - which the UI renders as a single empty state, not an
 * error.
 */
data class TheoreticalTimetable(
    val stopId: String,
    val lineNumber: String,
    /** 1-based direction id (GTFS `direction_id` + 1). */
    val directionId: Int,
    val date: LocalDate,
    /** Distinct terminus labels for this direction on this date (usually one; more == branch splits). */
    val destinationLabels: List<String>,
    val entries: List<TimetableEntry>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** One scheduled departure in a [TheoreticalTimetable]. */
data class TimetableEntry(
    /** Wall-clock departure (Europe/Paris). Already normalised: a source hour of 24/25 becomes 0/1 with [nextDay] set. */
    val time: LocalTime,
    /** True when the source hour was >= 24 (post-midnight service the morning after [TheoreticalTimetable.date]). */
    val nextDay: Boolean,
    /** This run's terminus label (the trip headsign - surfaces branch splits and short runs). */
    val destinationLabel: String?,
)

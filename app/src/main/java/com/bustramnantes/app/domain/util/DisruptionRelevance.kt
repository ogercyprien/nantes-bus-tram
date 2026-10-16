package com.bustramnantes.app.domain.util

import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.JourneySection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant

/**
 * How far ahead a notice that hasn't started yet is still worth showing ("à partir de demain 9h30"):
 * a demonstration announced for tomorrow matters today, works starting next month don't.
 */
val DISRUPTION_UPCOMING_WINDOW: Duration = 3.days

/**
 * The notices worth showing at [now]: active now, or starting within [DISRUPTION_UPCOMING_WINDOW].
 * Active ones first, then by start.
 */
fun List<Disruption>.current(now: Instant): List<Disruption> =
    filter { d -> d.isActiveAt(now) || d.nextStartAfter(now)?.let { it - now <= DISRUPTION_UPCOMING_WINDOW } == true }
        .sortedWith(compareBy<Disruption> { !it.isActiveAt(now) }.thenBy { it.nextStartAfter(now) ?: Instant.DISTANT_PAST })

/**
 * Notices relevant to a stop, split for display: [atStop] target one of its StopPlaces (a moved or
 * closed stop, a diversion skipping it); [onLines] target, as a whole, a line serving it.
 */
data class StopDisruptions(val atStop: List<Disruption>, val onLines: List<Disruption>) {
    val all: List<Disruption> get() = atStop + onLines
    val isEmpty: Boolean get() = atStop.isEmpty() && onLines.isEmpty()

    companion object {
        val NONE = StopDisruptions(emptyList(), emptyList())
    }
}

/** [current] notices for a stop with StopPlaces [memberIds] served by [servedLineIds]. */
fun List<Disruption>.forStop(memberIds: Collection<String>, servedLineIds: Collection<String>, now: Instant): StopDisruptions {
    val relevant = current(now)
    val atStop = relevant.filter { it.targetsStop(memberIds) }
    val onLines = relevant.filter { d -> d !in atStop && servedLineIds.any(d::targetsWholeLine) }
    return StopDisruptions(atStop, onLines)
}

/**
 * [current] notices for a favorite: a whole-stop favorite gets everything about the stop (see
 * [forStop]); a line favorite only what concerns that line there (the whole line, the line at this
 * stop, or every line at this stop).
 */
fun List<Disruption>.forFavorite(
    favorite: Favorite,
    memberIds: Collection<String>,
    servedLineIds: Collection<String>,
    now: Instant,
): List<Disruption> {
    val lineId = favorite.lineId ?: return forStop(memberIds, servedLineIds, now).all
    return current(now).filter { it.concernsLineAt(lineId, memberIds) }
}

/**
 * Notices concerning a ride while it runs: its line as a whole, its line (or every line) at its
 * boarding or alighting stop. A notice about an intermediate stop of the ride is left out (it doesn't
 * change where the rider gets on or off).
 */
fun List<Disruption>.forLeg(leg: JourneySection.Transit): List<Disruption> {
    val ends = listOfNotNull(leg.concernedStops.firstOrNull()?.stopId, leg.concernedStops.lastOrNull()?.stopId)
    return filter { it.overlaps(leg.departureTime, leg.arrivalTime) && it.concernsLineAt(leg.lineId, ends) }
}

/** Lines with a notice in force at [now] (anywhere on the line) - the warning dot on line badges. */
fun List<Disruption>.disruptedLineIds(now: Instant): Set<String> =
    filter { it.isActiveAt(now) }.flatMapTo(HashSet()) { it.lineIds }

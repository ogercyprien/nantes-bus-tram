package com.bustramnantes.app.presentation.stop

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.ServedLine
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.util.normalizeForMatch

/** Default cap on rows rendered per line+direction section ("Next 3-5 departures"). */
const val MAX_DEPARTURES_PER_GROUP = 5

/**
 * One line+direction section of the Stop Detail departures list ("grouped by line,
 * then by direction"). [departures] is already sorted soonest-first and capped to
 * [MAX_DEPARTURES_PER_GROUP].
 *
 * An **empty** [departures] means this line/direction serves the stop but has no upcoming
 * departures (typically end of service for the day) - the UI keeps the row visible with a
 * "fin de service" note rather than hiding the line. See [buildDepartureGroups].
 */
data class DepartureGroup(
    val lineId: String,
    val lineNumber: String,
    val lineName: String,
    val lineColor: String,
    val lineTextColor: String,
    val transportMode: TransportMode,
    val direction: Direction,
    val departures: List<Departure>,
) {
    /** Stable key identifying this line+direction pair, used for list keys and favorite matching. */
    val groupKey: String get() = "$lineId|${direction.code}"

    /** `true` when the line/direction is known but has no upcoming departures (end of service). */
    val isEndOfService: Boolean get() = departures.isEmpty()
}

/**
 * Sorts groups by line number (natural/numeric-aware: `2` before `10` before `C2`), then by
 * direction name. Shared by both [toDepartureGroups] and [buildDepartureGroups].
 */
private val groupComparator: Comparator<DepartureGroup> = compareBy(
    { it.lineNumber.toIntOrNull() == null },
    { it.lineNumber.toIntOrNull() ?: Int.MAX_VALUE },
    { it.lineNumber },
    { it.direction.name },
)

/**
 * Groups a flat departures list (as returned by [com.bustramnantes.app.domain.repository.TimetableRepository])
 * into [DepartureGroup]s, sorted by line number (natural/numeric-aware) then by
 * direction name, each capped to [maxPerGroup] soonest departures.
 *
 * This is the "live departures only" grouping - lines with no upcoming departure never appear.
 * Prefer [buildDepartureGroups] for Stop Detail, which additionally surfaces served-but-idle lines.
 */
fun List<Departure>.toDepartureGroups(maxPerGroup: Int = MAX_DEPARTURES_PER_GROUP): List<DepartureGroup> =
    buildDepartureGroups(servedLines = emptyList(), departures = this, maxPerGroup = maxPerGroup)

/**
 * Builds the full Stop Detail departures list: one [DepartureGroup] for **every**
 * line/direction the stop serves ([servedLines]), filled in with the soonest live [departures] for
 * that pair. A pair with no upcoming departure still gets a group (with an empty
 * [DepartureGroup.departures], i.e. [DepartureGroup.isEndOfService]) so the row stays visible with
 * a "fin de service" note instead of disappearing after the day's last bus.
 *
 * Any live departure whose line/direction is missing from [servedLines] (e.g. a newer feed than the
 * installed network) is still included as its own group - real departures are never dropped. When [servedLines] is empty this degrades to plain [toDepartureGroups] over
 * the live departures.
 *
 */
fun buildDepartureGroups(
    servedLines: List<ServedLine>,
    departures: List<Departure>,
    maxPerGroup: Int = MAX_DEPARTURES_PER_GROUP,
): List<DepartureGroup> {
    val departuresByKey: Map<Pair<String, String>, List<Departure>> =
        departures.groupBy { it.lineId to it.direction.code }
    val seenKeys = mutableSetOf<Pair<String, String>>()
    val groups = mutableListOf<DepartureGroup>()

    fun sortedCapped(deps: List<Departure>) = deps.sortedBy { it.nextDepartedTime }.take(maxPerGroup)

    // 1. One group per served line+direction, whether or not a bus is currently due.
    for (line in servedLines) {
        for (direction in line.directions) {
            val key = line.lineId to direction.code
            if (!seenKeys.add(key)) continue
            groups += DepartureGroup(
                lineId = line.lineId,
                lineNumber = line.lineNumber,
                lineName = line.lineName,
                lineColor = line.lineColor,
                lineTextColor = line.lineTextColor,
                transportMode = line.transportMode,
                direction = direction,
                departures = sortedCapped(departuresByKey[key].orEmpty()),
            )
        }
    }

    // 2. Safety net: any live departure not covered above - never hide a real, imminent bus.
    for ((key, deps) in departuresByKey) {
        if (!seenKeys.add(key)) continue
        val first = deps.first()
        groups += DepartureGroup(
            lineId = first.lineId,
            lineNumber = first.lineNumber,
            lineName = first.lineName,
            lineColor = first.lineColor,
            lineTextColor = first.lineTextColor,
            transportMode = first.transportMode,
            direction = first.direction,
            departures = sortedCapped(deps),
        )
    }

    return groups.sortedWith(groupComparator)
}

/**
 * This run's own destination when it is a **short run** - it ends before the terminus its block is
 * named after (e.g. a tram 2 ending at Hôtel Dieu, listed under "Gare de Pont Rousseau") - else null.
 * A destination equal to the direction name, or to one branch of a dual `"A / B"` direction name
 * (each run of line 1 "Beaujoire / Babinière" goes to one of the two), is not a short run. Short runs
 * stay in their direction block, with this label on the row.
 */
fun Departure.shortRunDestination(directionName: String): String? {
    val destination = destinationName?.takeIf { it.isNotBlank() } ?: return null
    val target = normalizeForMatch(destination)
    val names = (listOf(directionName) + directionName.split("/")).map { normalizeForMatch(it.trim()) }
    return if (target in names || names.all { it.isBlank() }) null else destination
}

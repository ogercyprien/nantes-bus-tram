package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection

/**
 * Drops the pointless access/egress walk from an itinerary when an endpoint is a bus/tram stop the
 * user explicitly picked.
 *
 * When you search *from a stop*, the planner still prefixes a short "walk to the stop" leg (you're
 * already there); when you search *to a stop*, it appends a "walk from the stop" leg (that's your
 * destination). Both are noise. This removes:
 * - a **leading** walk iff [fromStopId] is set and the following transit leg actually boards at that
 *   same stop, and
 * - a **trailing** walk iff [toStopId] is set and the preceding transit leg actually alights at that
 *   same stop.
 *
 * The "same stop" check (by [com.bustramnantes.app.domain.model.ConcernedStop.stopId], the same stop
 * id namespace as a picked stop's id) is what preserves the legitimate
 * case the user called out: if the journey walks to a *different* nearby stop to catch the bus, that
 * walk is kept. Walk-only journeys (no transit at all) are never touched.
 *
 * After trimming, the journey's `departureTime`/`arrivalTime` are re-anchored to the first/last
 * remaining transit leg so the summary reflects the real bus time (not the old walk start/end), and
 * `totalDurationSeconds` is recomputed to match.
 */
fun Journey.trimAccessEgressWalks(fromStopId: String?, toStopId: String?): Journey {
    if (sections.none { it is JourneySection.Transit }) return this

    var trimmed = sections

    val first = trimmed.firstOrNull()
    val afterFirst = trimmed.getOrNull(1)
    if (fromStopId != null &&
        first is JourneySection.Walking &&
        afterFirst is JourneySection.Transit &&
        afterFirst.concernedStops.firstOrNull()?.stopId == fromStopId
    ) {
        trimmed = trimmed.drop(1)
    }

    val last = trimmed.lastOrNull()
    val beforeLast = trimmed.getOrNull(trimmed.lastIndex - 1)
    if (toStopId != null &&
        last is JourneySection.Walking &&
        beforeLast is JourneySection.Transit &&
        beforeLast.concernedStops.lastOrNull()?.stopId == toStopId
    ) {
        trimmed = trimmed.dropLast(1)
    }

    if (trimmed == sections) return this

    val newDeparture = (trimmed.firstOrNull() as? JourneySection.Transit)?.departureTime ?: departureTime
    val newArrival = (trimmed.lastOrNull() as? JourneySection.Transit)?.arrivalTime ?: arrivalTime
    return copy(
        sections = trimmed,
        departureTime = newDeparture,
        arrivalTime = newArrival,
        totalDurationSeconds = (newArrival - newDeparture).inWholeSeconds.toInt().coerceAtLeast(0),
    )
}

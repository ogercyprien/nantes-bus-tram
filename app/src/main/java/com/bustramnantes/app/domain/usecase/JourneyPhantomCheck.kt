package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/**
 * Only legs departing within this window from now are checked against the boards. Beyond it no
 * departure is real-time (network sweeps: never more than 60 min ahead), so every slot is
 * theoretical and nothing can be told apart.
 */
val PHANTOM_CHECK_HORIZON = 60.minutes

/**
 * Without a trip id, a board departure within this of the leg's departure is taken to be the leg's
 * bus; the slack absorbs a prediction that moved.
 */
private val LEG_MATCH_WINDOW = 90.seconds

/**
 * How long a search may wait for the shared SIRI budget to cover the boarding stops. The itinerary is
 * computed locally in milliseconds, so this is the only latency added - kept short; past it the
 * check uses whatever complete boards exist (provisional boards can't reveal phantoms).
 */
const val PHANTOM_CHECK_MAX_WAIT_MS = 12_000L

/** True when any transit leg of this journey was found to be a suspected phantom. */
val Journey.usesSuspectedPhantom: Boolean
    get() = sections.any { it is JourneySection.Transit && it.isSuspectedPhantom }

/** Trip ids of the phantom legs of [journeys] (what the planner excludes on its next search). */
fun phantomTripIds(journeys: List<Journey>): Set<String> = journeys.asSequence()
    .flatMap { it.sections.asSequence() }
    .filterIsInstance<JourneySection.Transit>()
    .filter { it.isSuspectedPhantom }
    .mapNotNull { it.tripId }
    .toSet()

/**
 * The board departure that *is* [leg]'s bus, or `null` when the board doesn't list it (then nothing
 * is concluded). By trip id when both carry one (the local planner and SIRI boards share the
 * `"<trip>@<day>"` key); otherwise same line, same direction (`directionId - 1` == direction code),
 * within [LEG_MATCH_WINDOW], the departure of the same kind (real-time / theoretical) as the leg
 * winning, then the closest.
 */
fun matchLegOnBoard(leg: JourneySection.Transit, board: List<Departure>): Departure? {
    leg.tripId?.let { id -> board.firstOrNull { it.tripId == id }?.let { return it } }
    val directionCode = leg.directionId?.minus(1)?.toString() ?: return null
    val legIsRealtime = leg.timeSourceType == TimeSourceType.REALTIME
    return board
        .filter { it.lineNumber == leg.lineNumber && it.direction.code == directionCode }
        .filter { abs((it.nextDepartedTime - leg.departureTime).inWholeSeconds) <= LEG_MATCH_WINDOW.inWholeSeconds }
        .minWithOrNull(
            compareBy<Departure>(
                { it.isRealtime != legIsRealtime },
                { abs((it.nextDepartedTime - leg.departureTime).inWholeSeconds) },
            ),
        )
}

/**
 * For every transit leg departing within [PHANTOM_CHECK_HORIZON] of [now] whose boarding stop has a
 * board in [boards] (keyed by stop id): tags it when its bus is a suspected phantom, and when the
 * bus is tracked shows the predicted time (`timeSourceType = REALTIME`; the arrival is shifted by the
 * same delay). A leg without a board is left untouched.
 */
fun tagPhantomLegs(journeys: List<Journey>, boards: Map<String, List<Departure>>, now: Instant): List<Journey> =
    journeys.map { journey ->
        journey.copy(
            sections = journey.sections.map { section ->
                if (section !is JourneySection.Transit || !section.isCheckable(now)) return@map section
                val board = boards[section.boardingStopIdOrNull() ?: return@map section] ?: return@map section
                val match = matchLegOnBoard(section, board) ?: return@map section
                when {
                    match.isSuspectedPhantom -> section.copy(isSuspectedPhantom = true)
                    match.isRealtime && section.timeSourceType != TimeSourceType.REALTIME -> {
                        val shift = match.nextDepartedTime - section.departureTime
                        section.copy(
                            departureTime = match.nextDepartedTime,
                            arrivalTime = section.arrivalTime + shift,
                            timeSourceType = TimeSourceType.REALTIME,
                        )
                    }
                    else -> section
                }
            },
        )
    }

private fun JourneySection.Transit.isCheckable(now: Instant): Boolean =
    departureTime >= now - 1.minutes && departureTime - now <= PHANTOM_CHECK_HORIZON

private fun JourneySection.Transit.boardingStopIdOrNull(): String? =
    concernedStops.firstOrNull()?.stopId ?: boardingStopId

/**
 * Gets the real-time board of every boarding stop that has a leg departing within
 * [PHANTOM_CHECK_HORIZON] (one shared SIRI request for all of them) and applies it - see
 * [tagPhantomLegs]. Best-effort: a stop whose board is only provisional simply isn't checked.
 */
class CheckJourneyPhantomsUseCase @Inject constructor(
    private val realtime: RealtimeBoardRepository,
) {
    /** Whether [invoke] would ask for any board (a leg departs within [PHANTOM_CHECK_HORIZON]). */
    fun wouldCheck(journeys: List<Journey>, now: Instant): Boolean = checkedStopIds(journeys, now).isNotEmpty()

    private fun checkedStopIds(journeys: List<Journey>, now: Instant): Set<String> =
        journeys.asSequence()
            .flatMap { it.sections.asSequence() }
            .filterIsInstance<JourneySection.Transit>()
            .filter { it.isCheckable(now) }
            .mapNotNull { it.boardingStopIdOrNull() }
            .toSet()

    suspend operator fun invoke(journeys: List<Journey>, now: Instant): List<Journey> {
        val stopIds = journeys.asSequence()
            .flatMap { it.sections.asSequence() }
            .filterIsInstance<JourneySection.Transit>()
            .filter { it.isCheckable(now) }
            .mapNotNull { it.boardingStopIdOrNull() }
            .toSet()
        if (stopIds.isEmpty()) return journeys
        val boards = realtime.awaitRealtime(stopIds, RealtimePriority.ITINERARY, PHANTOM_CHECK_MAX_WAIT_MS)
            .filterValues { !it.provisional }
            .mapValues { it.value.departures }
        return tagPhantomLegs(journeys, boards, now)
    }
}

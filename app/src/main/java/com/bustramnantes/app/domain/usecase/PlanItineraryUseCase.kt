package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.repository.ItineraryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The result of [PlanItineraryUseCase]: the journeys to show, plus how many suggested journeys were
 * dropped because they rode a suspected phantom bus ([avoidedPhantomJourneys]), so the screen can say
 * so. When every option found rode a phantom, those are returned anyway - tagged, never hidden
 * silently - and [onlyPhantomJourneys] is set.
 */
data class ItineraryPlan(
    val journeys: List<Journey>,
    val avoidedPhantomJourneys: Int = 0,
    val onlyPhantomJourneys: Boolean = false,
    /**
     * These are the timetable journeys, shown at once; the real-time check of their near-term legs is
     * still running and a final plan follows on [PlanItineraryUseCase.stream].
     */
    val realtimeCheckPending: Boolean = false,
)

/** At most this many extra searches are spent replacing phantom journeys (each is a local RAPTOR run). */
private const val MAX_PHANTOM_RETRIES = 2

/**
 * Plans journeys between two points on the device ([ItineraryRepository], RAPTOR over the GTFS
 * network), then **replaces any journey that relies on a suspected phantom bus**.
 *
 * Every transit leg departing within [PHANTOM_CHECK_HORIZON] is checked against its boarding stop's
 * real-time board ([CheckJourneyPhantomsUseCase], one shared SIRI request), which also puts the
 * predicted times on tracked legs. Journeys riding a phantom are dropped and the search re-run with
 * those trips **excluded** - trivial now that the planner is local (the official API had no exclusion
 * parameter, which forced a "search again from just too late" workaround) - up to [MAX_PHANTOM_RETRIES]
 * times, merging the new clean journeys in. Searches far from now are untouched (no leg in the
 * horizon, so no board is even asked for).
 *
 * [stream] emits the **timetable journeys first** (computed locally in milliseconds,
 * [ItineraryPlan.realtimeCheckPending] set) and then the checked plan - the check may wait up to
 * [PHANTOM_CHECK_MAX_WAIT_MS] per round for the shared SIRI slot, and the screen shouldn't. When no leg
 * needs checking there is a single, final emission.
 */
class PlanItineraryUseCase @Inject constructor(
    private val itineraryRepository: ItineraryRepository,
    private val checkJourneyPhantoms: CheckJourneyPhantomsUseCase,
) {
    /** The final (checked) plan - the last emission of [stream]. */
    suspend operator fun invoke(
        fromLabel: String,
        fromLat: Double,
        fromLon: Double,
        toLabel: String,
        toLat: Double,
        toLon: Double,
        timePreference: ItineraryTimePreference = ItineraryTimePreference.Now,
        fromStopId: String? = null,
        toStopId: String? = null,
        now: Instant = Clock.System.now(),
    ): DataResult<ItineraryPlan> =
        stream(fromLabel, fromLat, fromLon, toLabel, toLat, toLon, timePreference, fromStopId, toStopId, now).last()

    /** Timetable journeys first, then the real-time-checked plan (see the class doc). */
    fun stream(
        fromLabel: String,
        fromLat: Double,
        fromLon: Double,
        toLabel: String,
        toLat: Double,
        toLon: Double,
        timePreference: ItineraryTimePreference = ItineraryTimePreference.Now,
        fromStopId: String? = null,
        toStopId: String? = null,
        now: Instant = Clock.System.now(),
    ): Flow<DataResult<ItineraryPlan>> = flow {
        suspend fun searchRaw(excluded: Set<String>): DataResult<List<Journey>> =
            when (
                val result = itineraryRepository.searchJourneys(
                    fromLabel = fromLabel,
                    fromLat = fromLat,
                    fromLon = fromLon,
                    toLabel = toLabel,
                    toLat = toLat,
                    toLon = toLon,
                    timePreference = timePreference,
                    fromStopId = fromStopId,
                    toStopId = toStopId,
                    excludedTripIds = excluded,
                )
            ) {
                is DataResult.Success -> DataResult.Success(
                    result.data.map { it.trimAccessEgressWalks(fromStopId = fromStopId, toStopId = toStopId) },
                )
                is DataResult.Error -> result
            }

        val raw = when (val result = searchRaw(emptySet())) {
            is DataResult.Success -> result.data
            is DataResult.Error -> { emit(result); return@flow }
        }
        val timetableOrder = raw.sortedWith(compareBy({ it.departureTime }, { it.arrivalTime }))
        if (!checkJourneyPhantoms.wouldCheck(raw, now)) {
            emit(DataResult.Success(ItineraryPlan(journeys = timetableOrder)))
            return@flow
        }
        emit(DataResult.Success(ItineraryPlan(journeys = timetableOrder, realtimeCheckPending = true)))

        val first = checkJourneyPhantoms(raw, now)
        val clean = first.filterNot { it.usesSuspectedPhantom }.toMutableList()
        val phantoms = first.filter { it.usesSuspectedPhantom }
        val excluded = phantomTripIds(phantoms).toMutableSet()
        var pending = phantoms
        for (attempt in 1..MAX_PHANTOM_RETRIES) {
            if (pending.isEmpty() || excluded.isEmpty()) break
            // A failed retry just keeps the clean journeys found so far - it never fails the search.
            val more = (searchRaw(excluded) as? DataResult.Success)?.data?.let { checkJourneyPhantoms(it, now) } ?: break
            val known = clean.map { it.signature() }.toSet()
            clean += more.filter { !it.usesSuspectedPhantom && it.signature() !in known }
            pending = more.filter { it.usesSuspectedPhantom }
            val newlyExcluded = phantomTripIds(pending) - excluded
            if (newlyExcluded.isEmpty()) break
            excluded += newlyExcluded
        }
        emit(
            DataResult.Success(
                if (clean.isEmpty() && phantoms.isNotEmpty()) {
                    ItineraryPlan(journeys = phantoms, onlyPhantomJourneys = true)
                } else {
                    ItineraryPlan(
                        journeys = clean.sortedWith(compareBy({ it.departureTime }, { it.arrivalTime })),
                        avoidedPhantomJourneys = phantoms.size,
                    )
                },
            ),
        )
    }
}

/**
 * Identity of a journey across searches: the buses it rides (line + boarding time). A walk-only
 * journey is identified by its arrival.
 */
private fun Journey.signature(): List<Any> {
    val rides = sections.filterIsInstance<JourneySection.Transit>().map { it.lineNumber to it.departureTime }
    return rides.ifEmpty { listOf(arrivalTime) }
}

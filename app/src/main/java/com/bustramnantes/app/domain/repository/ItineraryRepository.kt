package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.Journey

/**
 * Itinerary search, computed **on the device** from the GTFS network (RAPTOR) - offline, any date
 * the installed feed covers. Never cached.
 */
interface ItineraryRepository {

    /**
     * Journeys between two points. [timePreference] selects leave now / leave at / arrive by. When an
     * endpoint is a stop the user picked ([fromStopId]/[toStopId]), its quays are used with no walk
     * (nearby stops stay reachable on foot). [excludedTripIds] are runs never to board (see
     * [com.bustramnantes.app.domain.model.JourneySection.Transit.tripId]) - the phantom-bus avoidance.
     * A successful empty list means no journey (e.g. a date outside the feed).
     */
    suspend fun searchJourneys(
        fromLabel: String,
        fromLat: Double,
        fromLon: Double,
        toLabel: String,
        toLat: Double,
        toLon: Double,
        timePreference: ItineraryTimePreference = ItineraryTimePreference.Now,
        fromStopId: String? = null,
        toStopId: String? = null,
        excludedTripIds: Set<String> = emptySet(),
    ): DataResult<List<Journey>>
}

package com.bustramnantes.app.domain.model

import kotlinx.datetime.Instant

/**
 * One complete itinerary solution returned by `GET /api/itinerary/search`.
 * Up to 12 of these are returned per search; never cached.
 */
data class Journey(
    val sections: List<JourneySection>,
    val totalDurationSeconds: Int,
    val departureTime: Instant,
    val arrivalTime: Instant,
    val transferCount: Int,
)

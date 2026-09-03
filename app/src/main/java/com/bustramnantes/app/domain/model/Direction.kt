package com.bustramnantes.app.domain.model

/**
 * A line direction: [code] is the GTFS `direction_id` (`"0"`/`"1"`), [name] its canonical headsign.
 * Referenced by [Favorite.direction] / [Departure.direction].
 */
data class Direction(
    val code: String,
    val name: String,
)

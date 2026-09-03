package com.bustramnantes.app.domain.model

/**
 * A Naolib network line, from the GTFS `routes.txt`. [id] is the GTFS route id
 * `FR_NAOLIB:Line:<short name>` ([idFor]).
 *
 * [color] / [textColor] are hex strings without a leading `#` (e.g. `"EE7402"`), matching the
 * cache table's documented format - contrast this with [Departure.lineColor], which mirrors the
 * timetable DTO's `#`-prefixed format.
 */
data class Line(
    val id: String,
    val shortName: String,
    val longName: String,
    val color: String,
    val textColor: String,
    val mode: TransportMode,
    /**
     * The line's directions (both ends, e.g. `Le Cardo` / `Gare Sud`), from the GTFS headsigns, so
     * Stop Detail can pre-render a line's direction blocks before (or without) any network while the
     * live departure times load. Empty if unknown.
     */
    val directions: List<Direction> = emptyList(),
) {
    companion object {
        /** The line id for a short name (`"C1"` -> `"FR_NAOLIB:Line:C1"`). */
        fun idFor(shortName: String): String = "FR_NAOLIB:Line:$shortName"
    }
}

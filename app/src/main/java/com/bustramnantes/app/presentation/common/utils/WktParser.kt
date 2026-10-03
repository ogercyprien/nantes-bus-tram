package com.bustramnantes.app.presentation.common.utils

/**
 * Dependency-free parser for the `LINESTRING (lon lat, lon lat, …)` WKT strings of itinerary legs
 * (ride shape slices, walk lines). Pure Kotlin,
 * no Android/graphics dependency, so it is usable from the Map and Itinerary feature modules
 * (and unit-testable on the plain JVM) without pulling in any geometry library.
 *
 * WKT coordinate order is `X Y` (i.e. longitude, latitude); this function flips each pair so the
 * result is ready to feed to map APIs that expect `(lat, lon)`.
 *
 * Malformed/empty input returns an empty list rather than throwing.
 *
 * @return a list of `(latitude, longitude)` pairs, in the same order as the WKT string.
 */
fun parseWktLineString(wkt: String): List<Pair<Double, Double>> {
    val start = wkt.indexOf('(')
    val end = wkt.lastIndexOf(')')
    if (start == -1 || end == -1 || end <= start) return emptyList()

    val body = wkt.substring(start + 1, end)
    return body.split(',').mapNotNull { rawPoint ->
        val tokens = rawPoint.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 2) return@mapNotNull null
        val lon = tokens[0].toDoubleOrNull() ?: return@mapNotNull null
        val lat = tokens[1].toDoubleOrNull() ?: return@mapNotNull null
        lat to lon
    }
}

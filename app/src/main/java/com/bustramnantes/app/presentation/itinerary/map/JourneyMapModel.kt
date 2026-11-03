package com.bustramnantes.app.presentation.itinerary.map

import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.presentation.common.utils.parseWktLineString

/** A WGS84 point. */
data class MapPoint(val lat: Double, val lon: Double)

/**
 * One leg of a journey as the itinerary maps draw it: a ride along its street path (in its line
 * colour, `#RRGGBB`) or a walk. [lineNumber]/[textColor] label a ride.
 */
data class JourneyMapLeg(
    val isWalk: Boolean,
    val points: List<MapPoint>,
    val color: String,
    val textColor: String = "#FFFFFF",
    val lineNumber: String? = null,
)

/** A stop a ride serves; [isTransfer] = boarding or alighting (drawn larger, tappable alike). */
data class JourneyMapStop(
    val stopId: String,
    val name: String,
    val point: MapPoint,
    val isTransfer: Boolean,
    val color: String,
)

/** Everything the journey map draws for one [Journey]. [origin]/[destination] = the A and B markers. */
data class JourneyMapModel(
    val legs: List<JourneyMapLeg>,
    val stops: List<JourneyMapStop>,
    val origin: MapPoint,
    val destination: MapPoint,
) {
    /** Every point drawn, for fitting the camera. */
    val allPoints: List<MapPoint> get() = legs.flatMap { it.points } + origin + destination
}

/** Walks are drawn in this neutral dark grey (dotted). */
const val WALK_COLOR = "#455A64"

/**
 * The map geometry of [journey]: each ride along its path (`tripGeometryWkt`, the OSM street path -
 * see `TransitNetwork.ridePath`), each walk along its street path, every served stop, and A/B = the
 * first and last point of the journey (the searched address, or the first/last stop when the search
 * started/ended at a stop). Null when the journey has no drawable geometry at all.
 */
fun journeyMapModel(journey: Journey): JourneyMapModel? {
    val legs = ArrayList<JourneyMapLeg>()
    val stops = ArrayList<JourneyMapStop>()
    for (section in journey.sections) {
        when (section) {
            is JourneySection.Walking -> {
                val points = section.pathLinks.flatMap { parseWktLineString(it.geometryWkt) }.map { (lat, lon) -> MapPoint(lat, lon) }
                if (points.size >= 2) legs += JourneyMapLeg(isWalk = true, points = points, color = WALK_COLOR)
            }
            is JourneySection.Transit -> {
                val color = hexColor(section.lineColor)
                val points = parseWktLineString(section.tripGeometryWkt).map { (lat, lon) -> MapPoint(lat, lon) }
                    .ifEmpty { section.concernedStops.map { MapPoint(it.lat, it.lon) } }
                if (points.size >= 2) {
                    legs += JourneyMapLeg(
                        isWalk = false, points = points, color = color,
                        textColor = hexColor(section.lineTextColor, fallback = "#FFFFFF"), lineNumber = section.lineNumber,
                    )
                }
                section.concernedStops.forEachIndexed { i, s ->
                    stops += JourneyMapStop(s.stopId, s.name, MapPoint(s.lat, s.lon), i == 0 || i == section.concernedStops.lastIndex, color)
                }
            }
        }
    }
    val first = legs.firstOrNull()?.points?.firstOrNull() ?: return null
    val last = legs.last().points.last()
    return JourneyMapModel(legs, stops, first, last)
}

/** `"007A45"` or `"#007A45"` -> `"#007A45"`; blank/invalid -> [fallback]. */
internal fun hexColor(raw: String, fallback: String = "#5C6BC0"): String {
    val hex = raw.trim().removePrefix("#")
    return if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#${hex.uppercase()}" else fallback
}

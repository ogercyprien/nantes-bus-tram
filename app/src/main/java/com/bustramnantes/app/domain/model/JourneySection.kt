package com.bustramnantes.app.domain.model

import kotlinx.datetime.Instant

/** Whether a [JourneySection.Transit] leg is backed by live vehicle data. */
enum class TimeSourceType {
    REALTIME,
    THEORICAL,
}

/**
 * One stop served by a [JourneySection.Transit] leg, from boarding to alighting. [stopId] is the
 * app's stop id ([com.bustramnantes.app.domain.model.Stop.id], a GTFS StopPlace id) - tapping it opens
 * the stop; [lat]/[lon] are the quay's position.
 */
data class ConcernedStop(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val departureTime: Instant?,
)

/**
 * One walking segment's geometry: a WKT `LINESTRING` (`lon lat, …`). The street path on the OSM walking
 * graph inside Nantes Métropole; a straight line between the two ends outside it.
 */
data class PathLink(
    val geometryWkt: String,
)

/**
 * One leg of a [Journey]: a public-transport ride or a walking segment. Built by the on-device
 * planner (`LocalItineraryRepository`, RAPTOR over the GTFS network).
 */
sealed interface JourneySection {

    /** A public-transport ride on a single line/direction (one trip). Colours without `#`. */
    data class Transit(
        val lineId: String,
        val lineNumber: String,
        val lineColor: String,
        val lineTextColor: String,
        val transportMode: TransportMode,
        val directionName: String,
        val concernedStops: List<ConcernedStop>,
        /** WKT `LINESTRING` for the ride's path: the GTFS shape between the two stops. */
        val tripGeometryWkt: String,
        val timeSourceType: TimeSourceType,
        val departureTime: Instant,
        val arrivalTime: Instant,
        /** 1-based direction id (GTFS `direction_id` + 1, the timetable route's convention). */
        val directionId: Int? = null,
        /** Stop id of the boarding stop (same as `concernedStops.first().stopId`). */
        val boardingStopId: String? = null,
        /** Timetabled departure; [departureTime] may be a real-time one. */
        val scheduledDepartureTime: Instant? = null,
        /**
         * The run, as `"<trip>@<service day>"` (the key boards use too) - lets the phantom check match
         * the leg to its bus on the board exactly, and lets the planner exclude that trip.
         */
        val tripId: String? = null,
        /**
         * The boarding stop's real-time board lists this very departure as a suspected phantom (see
         * [com.bustramnantes.app.domain.util.tagSuspectedPhantoms]) - set by the itinerary phantom
         * check, only for legs departing soon enough for the board to know.
         */
        val isSuspectedPhantom: Boolean = false,
    ) : JourneySection

    /** A walking segment between two points. */
    data class Walking(
        val pathLinks: List<PathLink>,
        /** Compass bearing in degrees (start -> end) - drives the "vers le nord" hint. */
        val magneticDirection: Double,
        val durationSeconds: Int,
        val distanceMeters: Int,
    ) : JourneySection
}

package com.bustramnantes.app.domain.model

/**
 * A rider-facing stop: one GTFS StopPlace (open-data `stops.txt`, `location_type=1`), or several
 * same-name StopPlaces a few metres apart merged into one map marker (see `mergeDuplicateStops`).
 *
 * [id] is the GTFS StopPlace id, e.g. `"FR_NAOLIB:StopPlace:506"` - the representative member of a
 * merged stop. [memberIds] lists every StopPlace id the stop stands for (always includes [id]):
 * departures, served lines and timetables use all of their quays, and a favorite saved on any member
 * belongs to this stop.
 *
 * [lat]/[lon] are the GTFS StopPlace position. The Plan tab draws its marker on the lines instead
 * (the line map's own marker, near the quays), except for a stop no drawn line serves.
 */
data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val linkedLines: List<String>,
    val accessible: Boolean,
    val memberIds: List<String> = listOf(id),
)

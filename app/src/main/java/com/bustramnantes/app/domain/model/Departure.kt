package com.bustramnantes.app.domain.model

import kotlinx.datetime.Instant

/** Where a [Departure]'s time comes from. */
enum class DepartureSource {
    /** Okina SIRI StopMonitoring - the nominal real-time source (same predictions as the official app). */
    SIRI,

    /** GTFS-RT TripUpdates overlay - the provisional stop-gap while the SIRI budget is spent. */
    GTFS_RT,

    /** The static GTFS timetable. */
    TIMETABLE,
}

/**
 * A single upcoming departure at a stop. Never cached - recomposed on every poll.
 *
 * [lineColor] / [lineTextColor] are hex strings WITH a leading `#` (contrast with [Line.color],
 * stored without it). [lineId] is the GTFS route id and [direction] the GTFS `direction_id` with its
 * canonical name, so departures match favorites and served lines directly.
 */
data class Departure(
    val lineId: String,
    val lineNumber: String,
    val lineName: String,
    val lineColor: String,
    val lineTextColor: String,
    val transportMode: TransportMode,
    val direction: Direction,
    val nextDepartedTime: Instant,
    val nextArrivalTime: Instant,
    /**
     * The time is a live prediction (the bouncing dot). From SIRI: the trip is listed **and** at most
     * 50 min away (see `BoardComposer`); from GTFS-RT: the trip has a prediction within that horizon.
     */
    val isRealtime: Boolean,
    /** Timetabled time, when known (SIRI `Aimed…`, GTFS). */
    val scheduledTime: Instant? = null,
    /** Identifies the run: GTFS trip id from SIRI or `"<trip>@<service day>"` from the timetable. */
    val tripId: String? = null,
    /** This run's own destination (short runs end before the direction's terminus). */
    val destinationName: String? = null,
    val source: DepartureSource = DepartureSource.TIMETABLE,
    /**
     * A theoretical time that is probably a **phantom** (no vehicle will come) - see
     * [com.bustramnantes.app.domain.util.tagSuspectedPhantoms] for the rule. Only set on boards with a
     * complete real-time source (fresh SIRI), never on provisional ones.
     */
    val isSuspectedPhantom: Boolean = false,
)

/**
 * A stop's composed departure board. [provisional] marks a board built without SIRI (GTFS-RT overlay
 * or timetable only, while the SIRI budget is spent): shown immediately, replaced at the next SIRI
 * slot, and never phantom-tagged (GTFS-RT omits ~27 % of tracked vehicles, so absence means nothing).
 *
 * A SIRI board may come from a **cached** snapshot a few minutes old ([stale], fetched at
 * [realtimeFetchedAtMs]) - shown at once rather than waiting for the next 30 s slot, without phantom
 * tagging (a bus not yet tracked then may be now). [realtimePending]: fresher real-time data has been
 * asked for and is on its way (false when Okina just failed - nothing is coming soon).
 */
data class StopBoard(
    val departures: List<Departure>,
    val provisional: Boolean,
    val source: DepartureSource,
    val realtimeFetchedAtMs: Long? = null,
    val stale: Boolean = false,
    val realtimePending: Boolean = false,
)

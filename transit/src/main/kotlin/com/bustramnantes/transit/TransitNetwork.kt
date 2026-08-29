package com.bustramnantes.transit

import com.bustramnantes.transit.walk.Footpaths

import java.time.LocalDate

/** GTFS `route_type` subset used by Naolib. */
enum class RouteMode(val gtfsType: Int) {
    TRAM(0),
    BUS(3),
    FERRY(4),
    ;

    companion object {
        /** Unknown types fall back to [BUS] (busways are type 3 already). */
        fun fromGtfs(type: Int): RouteMode = entries.firstOrNull { it.gtfsType == type } ?: BUS
    }
}

/**
 * One line. [shortName] is the rider-facing number ("3", "C1") - also the suffix of the GTFS id
 * `FR_NAOLIB:Line:<shortName>` ([id]). Colours are hex WITHOUT `#`, as in `routes.txt`.
 *
 * [directionNames] holds the canonical name of direction 0 and 1: the most common `trip_headsign`
 * among the line's trips in that direction (e.g. tram 1: "Beaujoire / Babinière"); the official app
 * names directions the same way on 158 of 159 line directions. Favorites key a direction on its
 * `"0"/"1"` code. Null when the line never runs that way.
 */
class Route(
    val shortName: String,
    val longName: String,
    val color: String,
    val textColor: String,
    val mode: RouteMode,
    val sortOrder: Int,
    val directionNames: Array<String?>,
) {
    val id: String get() = TransitNetwork.ROUTE_ID_PREFIX + shortName
}

/**
 * A GTFS StopPlace (`location_type=1`) - the rider-facing "stop" the map shows. [number] is the
 * numeric suffix of `FR_NAOLIB:StopPlace:<n>` (see [stopPlaceId]).
 *
 * [lat]/[lon] are the **display** coordinates: the official map's position when the seed [Crosswalk]
 * matched this stop (the raster line tiles are drawn against those), else the GTFS position.
 *
 * [aliases] are extra search keys (former names) - an empty list today, kept as a hook for a future
 * rename that reaches the riders before the GTFS.
 */
class StopPlace(
    val number: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val wheelchair: Boolean,
    val quays: IntArray,
    val aliases: List<String>,
    /** [lat]/[lon] come from the crosswalk (official map position), not from the GTFS. */
    val hasDisplayPosition: Boolean = false,
) {
    val id: String get() = TransitNetwork.stopPlaceId(number)
}

/** A stop pattern: the ordered quays of a route+direction, shared by all its trips. */
class Pattern(
    val route: Int,
    val directionId: Int,
    val headsign: String,
    val quays: IntArray,
    /** Index into [TransitNetwork.shapes], or -1. */
    val shape: Int,
)

/**
 * The whole static network, in memory, from the packed GTFS (see [NetworkFormat]). Arrays rather than
 * objects for the per-trip/per-quay data: there are ~42 k unique trips and the router scans them.
 *
 * Trips are the **de-duplicated** GTFS trips: the producer repeats each vehicle journey once per
 * service-period chunk (`...-Dimanche-01`, `-03`, …); identical (pattern, times) trips are merged and
 * their services OR-ed into one [serviceSets] day bitset over [validFrom] .. +[dayCount].
 *
 * Times are seconds after the service day's midnight (noon − 12 h, GTFS semantics), and can exceed
 * 24 h for after-midnight runs.
 */
class TransitNetwork(
    val formatVersion: Int,
    /** GTFS file id from the open-data portal (the publication's change key), or "" for fixtures. */
    val feedId: String,
    val validFrom: LocalDate,
    val dayCount: Int,
    val timezone: String,
    val routes: List<Route>,
    val stopPlaces: List<StopPlace>,
    val quayNumber: IntArray,
    val quayStopPlace: IntArray,
    val quayLat: DoubleArray,
    val quayLon: DoubleArray,
    val patterns: List<Pattern>,
    /** Per unique trip: pattern index. Trips are sorted by (pattern, first departure). */
    val tripPattern: IntArray,
    /** Per unique trip: departure from the first quay, seconds after service-day midnight. */
    val tripStart: IntArray,
    /** Per unique trip: running-time profile index into [profileArrivals]/[profileDepartures]. */
    val tripProfile: IntArray,
    /** Per unique trip: index into [serviceSets]. */
    val tripServiceSet: IntArray,
    /** Per unique trip: headsign (index into [headsigns]). */
    val tripHeadsign: IntArray,
    val headsigns: Array<String>,
    /** Arrival offset (s) from the trip start, per pattern position. */
    val profileArrivals: Array<IntArray>,
    /** Departure offset (s) from the trip start, per pattern position. */
    val profileDepartures: Array<IntArray>,
    /** Day bitsets: bit d = active on validFrom + d. */
    val serviceSets: Array<LongArray>,
    /** Original vehicle-journey numbers (sorted, may repeat) ... */
    val vjNumbers: IntArray,
    /** ... and the unique trip each one maps to (same index). */
    val vjTrips: IntArray,
    /** Shape polylines, lat/lon interleaved. */
    val shapes: Array<DoubleArray>,
    /**
     * Walking transfers between quays computed on the OSM walking graph; null for a network converted
     * without the graph (fixtures) - the router then uses [Footpaths.crowFly].
     */
    val footpaths: Footpaths? = null,
) {
    val validUntil: LocalDate get() = validFrom.plusDays(dayCount.toLong() - 1)

    val quayCount: Int get() = quayNumber.size
    val tripCount: Int get() = tripPattern.size

    /** First trip index of each pattern ([tripPattern] is sorted); size = patterns + 1. */
    val patternTripStart: IntArray = IntArray(patterns.size + 1).also { start ->
        var t = 0
        for (p in patterns.indices) {
            start[p] = t
            while (t < tripPattern.size && tripPattern[t] == p) t++
        }
        start[patterns.size] = tripPattern.size
    }

    private val stopPlaceIndexByNumber: Map<Int, Int> =
        stopPlaces.indices.associateBy { stopPlaces[it].number }
    private val quayIndexByNumber: Map<Int, Int> = quayNumber.indices.associateBy { quayNumber[it] }
    private val routeIndexByShortName: Map<String, Int> = routes.indices.associateBy { routes[it].shortName }

    /** Patterns through each quay, with the quay's position in it: (pattern shl 8) or position... kept as pairs. */
    val quayPatterns: Array<IntArray> = run {
        val lists = Array(quayCount) { ArrayList<Int>() }
        patterns.forEachIndexed { p, pattern ->
            pattern.quays.forEachIndexed { pos, q -> lists[q] += p; lists[q] += pos }
        }
        Array(quayCount) { lists[it].toIntArray() }
    }

    fun stopPlaceIndex(id: String): Int? = parseStopPlaceNumber(id)?.let { stopPlaceIndexByNumber[it] }
    fun stopPlaceIndexByNumber(number: Int): Int? = stopPlaceIndexByNumber[number]
    fun quayIndex(id: String): Int? = parseQuayNumber(id)?.let { quayIndexByNumber[it] }
    fun quayIndexByNumber(number: Int): Int? = quayIndexByNumber[number]
    fun routeIndex(shortName: String): Int? = routeIndexByShortName[shortName]

    fun quayId(quay: Int): String = QUAY_ID_PREFIX + quayNumber[quay]

    /** Day offset of [date] in the bitsets, or -1 when outside the validity window. */
    fun dayIndex(date: LocalDate): Int {
        val d = (date.toEpochDay() - validFrom.toEpochDay()).toInt()
        return if (d in 0 until dayCount) d else -1
    }

    /** `true` when [date] is inside the feed's validity window. */
    fun covers(date: LocalDate): Boolean = dayIndex(date) >= 0

    fun isTripActive(trip: Int, dayIndex: Int): Boolean {
        if (dayIndex < 0 || dayIndex >= dayCount) return false
        val bits = serviceSets[tripServiceSet[trip]]
        return (bits[dayIndex ushr 6] ushr (dayIndex and 63)) and 1L == 1L
    }

    fun departureAt(trip: Int, position: Int): Int = tripStart[trip] + profileDepartures[tripProfile[trip]][position]
    fun arrivalAt(trip: Int, position: Int): Int = tripStart[trip] + profileArrivals[tripProfile[trip]][position]

    /**
     * The unique trips an original vehicle-journey number maps to (several when the journey's times
     * differ between service periods). Callers pick the one active on the service day.
     */
    fun tripsForVehicleJourney(vj: Int): IntArray {
        var lo = java.util.Arrays.binarySearch(vjNumbers, vj)
        if (lo < 0) return IntArray(0)
        while (lo > 0 && vjNumbers[lo - 1] == vj) lo--
        var hi = lo
        while (hi < vjNumbers.size && vjNumbers[hi] == vj) hi++
        return vjTrips.copyOfRange(lo, hi)
    }

    /**
     * Resolves a GTFS/SIRI trip id (`FR_NAOLIB:VehicleJourney:<n>-<service>`, optional `:LOC`) to the
     * unique trip running on [serviceDay], or null when unknown (a newer/older publication).
     */
    fun resolveTripId(tripId: String, serviceDay: LocalDate): Int? {
        val vj = parseVehicleJourneyNumber(tripId) ?: return null
        val candidates = tripsForVehicleJourney(vj)
        if (candidates.isEmpty()) return null
        val day = dayIndex(serviceDay)
        return candidates.firstOrNull { isTripActive(it, day) }
    }

    /**
     * A quay of a feed appended to Naolib's (the Bacs de Loire - see `gtfs/AppendedFeed.kt`): numbered
     * from [EXTERNAL_NUMBER_BASE]. Okina's SIRI knows nothing about it.
     */
    fun isExternalQuay(quay: Int): Boolean = quayNumber[quay] >= EXTERNAL_NUMBER_BASE

    /** Routes that only serve [isExternalQuay] quays (the bacs): not Naolib lines. */
    val externalRoutes: Set<Int> by lazy {
        val naolib = HashSet<Int>()
        val any = HashSet<Int>()
        for (p in patterns) {
            any += p.route
            if (p.quays.any { !isExternalQuay(it) }) naolib += p.route
        }
        any - naolib
    }

    companion object {
        /** Quay/StopPlace numbers from here on belong to appended (non-Naolib) feeds. */
        const val EXTERNAL_NUMBER_BASE = 9_000_000
        const val ROUTE_ID_PREFIX = "FR_NAOLIB:Line:"
        const val STOP_PLACE_ID_PREFIX = "FR_NAOLIB:StopPlace:"
        const val QUAY_ID_PREFIX = "FR_NAOLIB:Quay:"
        const val VEHICLE_JOURNEY_PREFIX = "FR_NAOLIB:VehicleJourney:"

        fun stopPlaceId(number: Int): String = STOP_PLACE_ID_PREFIX + number

        fun parseStopPlaceNumber(id: String): Int? =
            if (id.startsWith(STOP_PLACE_ID_PREFIX)) id.substring(STOP_PLACE_ID_PREFIX.length).toIntOrNull() else null

        fun parseQuayNumber(id: String): Int? {
            if (!id.startsWith(QUAY_ID_PREFIX)) return null
            return id.substring(QUAY_ID_PREFIX.length).substringBefore(':').toIntOrNull()
        }

        /** `FR_NAOLIB:VehicleJourney:49794067-CR_26_27-...[:LOC]` -> 49794067. */
        fun parseVehicleJourneyNumber(tripId: String): Int? {
            if (!tripId.startsWith(VEHICLE_JOURNEY_PREFIX)) return null
            val rest = tripId.substring(VEHICLE_JOURNEY_PREFIX.length)
            val end = rest.indexOf('-').let { if (it < 0) rest.length else it }
            return rest.substring(0, end).toIntOrNull()
        }

        /** `FR_NAOLIB:Line:C1[:LOC]` -> "C1". */
        fun parseRouteShortName(lineRef: String): String =
            lineRef.removePrefix(ROUTE_ID_PREFIX).removeSuffix(":LOC")
    }
}

package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.data.network.TransitNetworkRepository
import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.PathLink
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.repository.ItineraryRepository
import com.bustramnantes.transit.haversineMeters
import com.bustramnantes.transit.ridePath
import com.bustramnantes.transit.serviceDayStart
import com.bustramnantes.transit.raptor.AccessPoint
import com.bustramnantes.transit.raptor.RaptorJourney
import com.bustramnantes.transit.raptor.RaptorLeg
import com.bustramnantes.transit.raptor.TripInstance
import com.bustramnantes.transit.realtime.BoardComposer
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaLocalDateTime

/**
 * On-device itinerary planning: RAPTOR ([com.bustramnantes.transit.raptor.RaptorRouter]) over the
 * GTFS network - offline, no server involved. Checked against the official planner with a live
 * comparison (`LiveLocalVsPlanAppItineraryTest`).
 *
 * - **Walking**: inside Nantes Métropole, walks follow the streets of the bundled OSM walking graph
 *   ([NetworkIndex.walkGraph]) - so a stop across the Loire or the railway isn't "next door" - at 4 km/h, plus a minute to board; outside it (or when a point is
 *   > 200 m from any walkable way) crow-fly × 1.3 and a straight line.
 * - **Access/egress**: a picked stop's quays at no walk, plus the quays its quays reach on foot (the
 *   precomputed quay-to-quay walks, ≤ 650 m); from an address/GPS point, every quay within
 *   [ACCESS_RADIUS_M] crow-fly, costed by a street search from the point (≤ radius × 1.3 walked) -
 *   widened to [ACCESS_RADIUS_WIDE_M] when that finds no journey (the nearest quays can belong to a
 *   line not running then).
 * - **Walk-only** option when the two points are within [WALK_ONLY_MAX_SECONDS] on foot.
 * - Up to [MAX_JOURNEYS] options over the next two hours (range RAPTOR); "Arriver à" searches back.
 */
@Singleton
class LocalItineraryRepository @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : ItineraryRepository {

    override suspend fun searchJourneys(
        fromLabel: String,
        fromLat: Double,
        fromLon: Double,
        toLabel: String,
        toLat: Double,
        toLon: Double,
        timePreference: ItineraryTimePreference,
        fromStopId: String?,
        toStopId: String?,
        excludedTripIds: Set<String>,
    ): DataResult<List<Journey>> = withContext(Dispatchers.Default) {
        runCatching {
            val index = networkRepository.index()
            planJourneys(index, fromLat, fromLon, toLat, toLon, timePreference, fromStopId, toStopId, excludedTripIds)
        }.toDataResult()
    }

    companion object {
        const val MAX_JOURNEYS = 6
        const val ACCESS_RADIUS_M = 1500.0
        const val ACCESS_RADIUS_WIDE_M = 3000.0
        const val STOP_ACCESS_RADIUS_M = 500.0
        const val WALK_ONLY_MAX_SECONDS = 30 * 60
    }
}

/**
 * The planning itself, pure over a [NetworkIndex] (unit-tested on the mini fixture). [now] is used
 * for [ItineraryTimePreference.Now].
 */
internal fun planJourneys(
    index: NetworkIndex,
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
    timePreference: ItineraryTimePreference,
    fromStopId: String? = null,
    toStopId: String? = null,
    excludedTripIds: Set<String> = emptySet(),
    now: Instant = Clock.System.now(),
): List<Journey> {
    val network = index.network
    val zone = ZoneId.of(network.timezone)
    val router = index.router
    val excluded = excludedTripIds.mapNotNull(::parseTripInstance).toSet()

    fun search(radius: Double): List<RaptorJourney> {
        val access = accessPoints(index, fromLat, fromLon, fromStopId, radius)
        val egress = accessPoints(index, toLat, toLon, toStopId, radius)
        return when (timePreference) {
            ItineraryTimePreference.Now ->
                router.departAfter(access, egress, now.epochSeconds, LocalItineraryRepository.MAX_JOURNEYS, excluded = excluded)
            is ItineraryTimePreference.DepartAt ->
                router.departAfter(access, egress, timePreference.dateTime.toJavaLocalDateTime().atZone(zone).toEpochSecond(), LocalItineraryRepository.MAX_JOURNEYS, excluded = excluded)
            is ItineraryTimePreference.ArriveBy ->
                router.arriveBy(access, egress, timePreference.dateTime.toJavaLocalDateTime().atZone(zone).toEpochSecond(), LocalItineraryRepository.MAX_JOURNEYS, excluded = excluded)
        }
    }
    val raptor = search(LocalItineraryRepository.ACCESS_RADIUS_M)
        .ifEmpty { search(LocalItineraryRepository.ACCESS_RADIUS_WIDE_M) }
    val journeys = raptor.map { toJourney(index, it, fromLat, fromLon, toLat, toLon) }.toMutableList()

    // Walk-only option for short distances (the official planner offered one too): along the streets
    // when both points are on the walking graph, else crow-fly.
    val maxWalkMeters = LocalItineraryRepository.WALK_ONLY_MAX_SECONDS * router.walk.speedMetersPerSecond
    val crow = haversineMeters(fromLat, fromLon, toLat, toLon)
    val street = if (crow <= maxWalkMeters) index.streetWalk(fromLat, fromLon, toLat, toLon, maxWalkMeters) else null
    val onGraph = index.walkGraph?.let { g -> g.snap(fromLat, fromLon) != null && g.snap(toLat, toLon) != null } == true
    val walkSeconds = when {
        street != null -> router.walk.walkedSeconds(street.meters)
        onGraph -> Int.MAX_VALUE // both on the streets, but more than 30 min apart on foot
        else -> router.walk.seconds(crow)
    }
    val sameStop = fromStopId != null && fromStopId == toStopId
    if (walkSeconds <= LocalItineraryRepository.WALK_ONLY_MAX_SECONDS && !sameStop) {
        val anchor = when (timePreference) {
            ItineraryTimePreference.Now -> now
            is ItineraryTimePreference.DepartAt -> Instant.fromEpochSeconds(timePreference.dateTime.toJavaLocalDateTime().atZone(zone).toEpochSecond())
            is ItineraryTimePreference.ArriveBy -> Instant.fromEpochSeconds(timePreference.dateTime.toJavaLocalDateTime().atZone(zone).toEpochSecond() - walkSeconds)
        }
        val walk = walking(
            street?.path ?: straight(fromLat, fromLon, toLat, toLon),
            walkSeconds,
            street?.meters?.toInt() ?: router.walk.walkMeters(crow),
        )
        journeys += Journey(listOf(walk), walkSeconds, anchor, Instant.fromEpochSeconds(anchor.epochSeconds + walkSeconds), 0)
    }
    return journeys
}

private fun accessPoints(index: NetworkIndex, lat: Double, lon: Double, stopId: String?, radius: Double): List<AccessPoint> {
    val router = index.router
    if (stopId != null) {
        val own = index.quays(stopId)
        if (own.isNotEmpty()) {
            // The stop's own quays, plus what they reach on foot (precomputed quay-to-quay walks).
            val best = HashMap<Int, Int>()
            val footpaths = router.footpaths
            for (q in own) for (i in footpaths.of(q)) {
                val to = footpaths.to[i]
                if (to in own) continue
                if (footpaths.meters[i] < (best[to] ?: Int.MAX_VALUE)) best[to] = footpaths.meters[i]
            }
            return own.map { AccessPoint(it, 0, 0) } +
                best.map { (q, m) -> AccessPoint(q, router.walk.walkedSeconds(m.toDouble()), m) }
        }
    }
    return streetAccess(index, lat, lon, radius) ?: router.quaysNear(lat, lon, radius)
}

/**
 * Quays within [radius] crow-fly of a point, costed by one street search from it; null when the point
 * is off the walking graph. A quay off the graph (outside the métropole) keeps its crow-fly cost; one
 * the streets don't reach within radius × 1.3 is left out (across the river, behind the railway).
 */
private fun streetAccess(index: NetworkIndex, lat: Double, lon: Double, radius: Double): List<AccessPoint>? {
    val graph = index.walkGraph ?: return null
    val origin = graph.snapAll(lat, lon).ifEmpty { return null }
    val router = index.router
    val network = index.network
    val search = graph.search(origin, radius * router.walk.detourFactor)
    val snaps = index.quaySnaps
    return (0 until network.quayCount).mapNotNull { q ->
        val crow = haversineMeters(lat, lon, network.quayLat[q], network.quayLon[q])
        if (crow > radius) return@mapNotNull null
        val snap = snaps[q].ifEmpty { return@mapNotNull AccessPoint(q, router.walk.seconds(crow), router.walk.walkMeters(crow)) }
        val walked = search.distanceTo(snap) ?: return@mapNotNull null
        AccessPoint(q, router.walk.walkedSeconds(walked), walked.toInt())
    }
}

/** `"<trip>@<yyyy-MM-dd>"` -> [TripInstance]. */
private fun parseTripInstance(key: String): TripInstance? {
    val at = key.indexOf('@')
    if (at < 0) return null
    val trip = key.substring(0, at).toIntOrNull() ?: return null
    val day = runCatching { LocalDate.parse(key.substring(at + 1)) }.getOrNull() ?: return null
    return TripInstance(trip, day)
}

private fun toJourney(index: NetworkIndex, j: RaptorJourney, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Journey {
    val network = index.network
    val sections = ArrayList<JourneySection>()
    for (leg in j.legs) {
        when (leg) {
            is RaptorLeg.Access -> if (leg.toEpochSecond > leg.fromEpochSecond) {
                sections += walking(
                    walkPath(index, fromLat, fromLon, network.quayLat[leg.quay], network.quayLon[leg.quay], leg.meters),
                    (leg.toEpochSecond - leg.fromEpochSecond).toInt(), leg.meters,
                )
            }
            is RaptorLeg.Egress -> if (leg.toEpochSecond > leg.fromEpochSecond) {
                sections += walking(
                    walkPath(index, network.quayLat[leg.quay], network.quayLon[leg.quay], toLat, toLon, leg.meters),
                    (leg.toEpochSecond - leg.fromEpochSecond).toInt(), leg.meters,
                )
            }
            is RaptorLeg.Transfer -> sections += walking(
                walkPath(index, network.quayLat[leg.fromQuay], network.quayLon[leg.fromQuay], network.quayLat[leg.toQuay], network.quayLon[leg.toQuay], leg.meters),
                (leg.toEpochSecond - leg.fromEpochSecond).toInt(), leg.meters,
            )
            is RaptorLeg.Ride -> sections += ride(index, leg)
        }
    }
    val departure = Instant.fromEpochSeconds(j.departureEpochSecond)
    val arrival = Instant.fromEpochSeconds(j.arrivalEpochSecond)
    return Journey(
        sections = sections,
        totalDurationSeconds = (j.arrivalEpochSecond - j.departureEpochSecond).toInt(),
        departureTime = departure,
        arrivalTime = arrival,
        transferCount = j.transfers,
    )
}

private fun ride(index: NetworkIndex, leg: RaptorLeg.Ride): JourneySection.Transit {
    val network = index.network
    val trip = leg.trip.trip
    val pattern = network.patterns[leg.pattern]
    val line = index.lines[pattern.route]
    val serviceStart = network.serviceDayStart(leg.trip.serviceDay)
    val stops = (leg.boardPosition..leg.alightPosition).map { pos ->
        val quay = pattern.quays[pos]
        val sp = network.stopPlaces[network.quayStopPlace[quay]]
        ConcernedStop(
            stopId = index.stop(sp.id)?.id ?: sp.id,
            name = sp.name,
            lat = network.quayLat[quay],
            lon = network.quayLon[quay],
            departureTime = Instant.fromEpochSeconds(serviceStart + network.departureAt(trip, pos)),
        )
    }
    val headsign = network.headsigns[network.tripHeadsign[trip]]
    return JourneySection.Transit(
        lineId = line.id,
        lineNumber = line.shortName,
        lineColor = line.color,
        lineTextColor = line.textColor,
        transportMode = line.mode,
        directionName = headsign.ifBlank { line.directions.firstOrNull { it.code == pattern.directionId.toString() }?.name.orEmpty() },
        concernedStops = stops,
        tripGeometryWkt = rideGeometry(index, leg),
        timeSourceType = TimeSourceType.THEORICAL,
        departureTime = Instant.fromEpochSeconds(leg.fromEpochSecond),
        arrivalTime = Instant.fromEpochSeconds(leg.toEpochSecond),
        directionId = pattern.directionId + 1,
        boardingStopId = stops.first().stopId,
        scheduledDepartureTime = Instant.fromEpochSeconds(leg.fromEpochSecond),
        tripId = BoardComposer.tripKey(trip, leg.trip.serviceDay),
    )
}

/**
 * The ride's path: the street path of each stop-to-stop hop from the boarding to the alighting quay
 * ([com.bustramnantes.transit.RideShapes], from OSM), or a straight line for a hop without one (ferries,
 * a hop newer than the shapes). The GTFS shapes are not used: Naolib's are one point per stop.
 */
private fun rideGeometry(index: NetworkIndex, leg: RaptorLeg.Ride): String {
    val network = index.network
    val path = network.ridePath(network.patterns[leg.pattern], leg.boardPosition, leg.alightPosition)
    return lineString(path.map { it[0] to it[1] })
}

/**
 * A walk leg's geometry: the street path when both ends are on the walking graph (searched a little
 * beyond the leg's own [meters] so the same path is found), else the straight line.
 */
private fun walkPath(index: NetworkIndex, lat1: Double, lon1: Double, lat2: Double, lon2: Double, meters: Int): List<DoubleArray> =
    index.streetWalk(lat1, lon1, lat2, lon2, meters * 1.2 + 100)?.path ?: straight(lat1, lon1, lat2, lon2)

private fun straight(lat1: Double, lon1: Double, lat2: Double, lon2: Double) = listOf(doubleArrayOf(lat1, lon1), doubleArrayOf(lat2, lon2))

/** A walking section along [path] (lat, lon pairs); its compass hint is the overall start -> end bearing. */
private fun walking(path: List<DoubleArray>, seconds: Int, meters: Int) = JourneySection.Walking(
    pathLinks = listOf(PathLink(lineString(path.map { it[0] to it[1] }))),
    magneticDirection = bearing(path.first()[0], path.first()[1], path.last()[0], path.last()[1]),
    durationSeconds = seconds,
    distanceMeters = meters,
)

/** WKT `LINESTRING (lon lat, …)` from (lat, lon) pairs. */
private fun lineString(points: List<Pair<Double, Double>>): String =
    points.joinToString(prefix = "LINESTRING (", postfix = ")") { (lat, lon) -> "$lon $lat" }

/** Initial compass bearing (degrees, 0 = north) from point 1 to point 2. */
private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

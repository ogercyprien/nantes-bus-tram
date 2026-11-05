package com.bustramnantes.app.data.network

import com.bustramnantes.app.data.repository.mergeDuplicateStops
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.ServedLine
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.transit.Route
import com.bustramnantes.transit.RouteMode
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.linkedRoutes
import com.bustramnantes.transit.raptor.RaptorRouter
import com.bustramnantes.transit.servedRoutes
import com.bustramnantes.transit.walk.Footpaths
import com.bustramnantes.transit.walk.Snap
import com.bustramnantes.transit.walk.WalkGraph
import com.bustramnantes.transit.withFootpaths

/**
 * The app's view of one [TransitNetwork]: domain [Stop]s/[Line]s built once per network, plus the
 * lookups the repositories need (stop id -> member StopPlaces -> quays). Immutable; a refreshed
 * network gets a fresh index.
 *
 * Stops are the GTFS StopPlaces, with same-name StopPlaces ≤ 150 m apart merged into one marker
 * ([mergeDuplicateStops]) - e.g. the two "Joliverie" StopPlaces (line 30 / lines 4, 30, 42, …).
 * Every member id resolves to the merged stop, so favorites, recent searches and deep links keep
 * working whichever id they hold.
 */
class NetworkIndex(
    val network: TransitNetwork,
    /** Loads the OSM walking graph (bundled asset) on first use; null = crow-fly walks everywhere. */
    private val walkGraphSource: () -> WalkGraph? = { null },
) {

    val lines: List<Line> = network.routes.mapIndexed { i, route ->
        route.toLine().let { if (i in network.externalRoutes) it.copy(mode = TransportMode.BAC) else it }
    }

    private val linesByShortName: Map<String, Line> = lines.associateBy { it.shortName }

    /** Display stops, merged. Only StopPlaces with at least one line (the feed has a few bare ones). */
    val stops: List<Stop>

    private val stopById: Map<String, Stop>
    private val stopPlaceIndicesByStopId: Map<String, IntArray>

    init {
        val raw = network.stopPlaces.indices.mapNotNull { i ->
            val sp = network.stopPlaces[i]
            val linked = network.linkedRoutes(i).map { network.routes[it].shortName }
            if (linked.isEmpty()) return@mapNotNull null
            Stop(
                id = sp.id,
                name = sp.name,
                lat = sp.lat,
                lon = sp.lon,
                linkedLines = linked,
                accessible = sp.wheelchair,
            )
        }
        stops = mergeDuplicateStops(raw)
        val byId = HashMap<String, Stop>()
        val members = HashMap<String, IntArray>()
        for (stop in stops) {
            val indices = stop.memberIds.mapNotNull { network.stopPlaceIndex(it) }.toIntArray()
            members[stop.id] = indices
            for (id in stop.memberIds) byId[id] = stop
        }
        stopById = byId
        stopPlaceIndicesByStopId = members
    }

    /** The display stop for a StopPlace id or a merged member's id. */
    fun stop(id: String): Stop? = stopById[id]

    /** StopPlace indices behind a display stop (any id [stop] accepts). */
    fun stopPlaces(id: String): IntArray = stop(id)?.let { stopPlaceIndicesByStopId[it.id] } ?: IntArray(0)

    /** Every quay of a display stop. */
    fun quays(id: String): IntArray = stopPlaces(id).flatMap { network.stopPlaces[it].quays.asIterable() }.toIntArray()

    fun line(shortName: String): Line? = linesByShortName[shortName]

    /**
     * The lines/directions boardable at a stop, from the GTFS patterns - service-hours independent and
     * terminus-aware (a direction the stop only ends is left out). Ordered like the line catalog.
     */
    fun servedLines(stopId: String): List<ServedLine> {
        val byRoute = sortedMapOf<Int, MutableSet<Int>>()
        for (sp in stopPlaces(stopId)) {
            for (served in network.servedRoutes(sp)) byRoute.getOrPut(served.route) { sortedSetOf() } += served.directions
        }
        return byRoute.map { (routeIndex, directions) ->
            val line = lines[routeIndex]
            ServedLine(
                lineId = line.id,
                lineNumber = line.shortName,
                lineName = line.longName,
                lineColor = "#" + line.color,
                lineTextColor = "#" + line.textColor,
                transportMode = line.mode,
                directions = directions.map { dir -> line.directionFor(dir) },
            )
        }
    }

    /** The OSM walking graph of the métropole, loaded on first use (null if unavailable). */
    val walkGraph: WalkGraph? by lazy { runCatching(walkGraphSource).getOrNull() }

    /** Each quay's attachments to [walkGraph] (empty: no graph, or the quay is off it - outside the métropole). */
    val quaySnaps: Array<List<Snap>> by lazy {
        val g = walkGraph
        Array(network.quayCount) { q -> g?.snapAll(network.quayLat[q], network.quayLon[q], Footpaths.QUAY_SNAP_METERS).orEmpty() }
    }

    /**
     * Lazily-built router. Quay-to-quay walks come from the packed network (computed on the walking
     * graph at pack/convert time); a network without them (format v1) gets them computed here once,
     * else falls back to crow-fly.
     */
    val router: RaptorRouter by lazy {
        val withFootpaths = if (network.footpaths == null) {
            walkGraph?.let { network.withFootpaths(Footpaths.compute(network, it)) } ?: network
        } else {
            network
        }
        RaptorRouter(withFootpaths)
    }

    /**
     * A walk between two points on the street network: the path (lat/lon pairs, from the first point
     * to the second) and its walked metres, or null when either end is off the graph or the walk is
     * longer than [maxMeters] - callers then fall back to a straight line.
     */
    fun streetWalk(lat1: Double, lon1: Double, lat2: Double, lon2: Double, maxMeters: Double): StreetWalk? {
        val g = walkGraph ?: return null
        val a = g.snapAll(lat1, lon1).ifEmpty { return null }
        val b = g.snapAll(lat2, lon2).ifEmpty { return null }
        val search = g.search(a, maxMeters)
        val meters = search.distanceTo(b) ?: return null
        val path = search.pathTo(b) ?: return null
        return StreetWalk(path, meters)
    }
}

/** A walk on the street network: its polyline (lat, lon pairs) and walked length. */
class StreetWalk(val path: List<DoubleArray>, val meters: Double)

/** The domain [Direction] for GTFS `direction_id` [dir] of this line (code = `"0"`/`"1"`). */
fun Line.directionFor(dir: Int): Direction =
    directions.firstOrNull { it.code == dir.toString() } ?: Direction(dir.toString(), "")

fun RouteMode.toTransportMode(): TransportMode = when (this) {
    RouteMode.TRAM -> TransportMode.TRAM
    RouteMode.BUS -> TransportMode.BUS
    RouteMode.FERRY -> TransportMode.FERRY
}

private fun Route.toLine(): Line = Line(
    id = id,
    shortName = shortName,
    longName = longName,
    color = color,
    textColor = textColor,
    mode = mode.toTransportMode(),
    directions = (0..1).mapNotNull { dir -> directionNames[dir]?.let { Direction(dir.toString(), it) } },
)

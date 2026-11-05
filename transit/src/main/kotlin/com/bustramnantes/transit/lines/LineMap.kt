package com.bustramnantes.transit.lines

import com.bustramnantes.transit.TransitNetwork
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * The whole network's line map for one **layout zoom**: corridors as segments, each carrying an
 * ordered set of lines drawn side by side, plus the stop markers. Built from open data only (the GTFS
 * patterns and their OSM ride paths) by [LineMapBuilder]; replaces the official raster line tiles.
 * The design and the reason behind every rule are in `README.md` next to this file.
 *
 * Geometry is in Web Mercator metres ([WebMercator]). Lanes are **not** offset here: their order is
 * stored and [LaneGeometry] offsets them, in dp, for the zoom being drawn. One layout serves a range
 * of draw zooms ([LineMapSet.layoutFor]).
 *
 * @property zoom the layout zoom this map was built for (its merge distances are that zoom's dp).
 * @property routes every route of the network, in network order; a segment's [Segment.order] holds
 *   indices into it. Routes the map doesn't draw ([LineStyle.Kind.HIDDEN]) are listed all the same.
 * @property nodeX node x, only for the nodes segments start or end at (junctions).
 */
class LineMap(
    val zoom: Int,
    val routes: List<MapRoute>,
    val nodeX: DoubleArray,
    val nodeY: DoubleArray,
    val segments: List<Segment>,
    val transitions: List<Transition>,
    /** One per StopPlace with a line drawn there, two (or more) when its directions part (see [StopMarker]). */
    val stops: List<StopMarker>,
) {
    val routeCount: Int get() = routes.size

    /** How each line is drawn (widths, kinds), shared by the builder, the orderer and [LaneGeometry]. */
    val style: LineStyle by lazy { LineStyle(routes) }

    /**
     * A chain of points between two junctions ([first], [last]) carrying one set of lines.
     * [order] lists route indices left to right, travelling first -> last; [LaneOrderer] sets it.
     * [x]/[y] are the simplified centreline (Douglas-Peucker at a quarter dp of the layout zoom).
     */
    class Segment(val first: Int, val last: Int, val x: DoubleArray, val y: DoubleArray, var order: IntArray)

    /**
     * [route] runs from segment end [endA] into segment end [endB] through junction [node].
     * An end is `segment * 2` (its first point) or `segment * 2 + 1` (its last point).
     */
    class Transition(val node: Int, val endA: Int, val endB: Int, val route: Int)

    /**
     * A stop's marker at ([x], [y]), on a drawn line.
     *
     * [hub]: a station (many lines, quays spread out, see [LineMapBuilder.HUB_MIN_LINES]); from
     * [HUB_DASH_MIN_ZOOM] it is drawn as a dash across its widest bundle, segment [dashSegment], whose
     * direction at the marker is [dashAngle] (radians, Mercator x/y; the dash is perpendicular to it).
     * Below that zoom stations are only a few dp apart and their dashes piled up across each other's
     * bundles (St-Nicolas over the Bretagne crossing at z13), so a hub is a dot there.
     *
     * A stop whose two directions run on different streets gets a marker on each (Toutes Joies: 26 and
     * C8 stop 220 m apart, one direction on each street), when they are far enough apart to read as
     * two ([LineMapBuilder.SPLIT_STOP_DP]). [stopId] is the GTFS StopPlace id, so a tap opens the stop.
     */
    class StopMarker(
        val stopId: String,
        val x: Double,
        val y: Double,
        val hub: Boolean,
        val dashSegment: Int = -1,
        val dashAngle: Double = 0.0,
    )

    /** Lane crossings left at junctions with the current orders (the orderer's cost; regression budget). */
    fun crossings(): Int = LaneOrderer(this).totalCost()

    companion object {
        /** Stations are dashes from this zoom, dots below (see [StopMarker.hub]). */
        const val HUB_DASH_MIN_ZOOM = 14.0
    }
}

/**
 * A route as the line map needs it, self-contained so a stored map ([LineMapFormat]) draws without
 * the network it was built from (the previous layout stays on screen while a new one is computed).
 * [color] is hex without `#`, as in the GTFS.
 */
class MapRoute(val id: String, val shortName: String, val color: String, val kind: LineStyle.Kind) {
    companion object {
        /** The routes of [net], in network order, with their [LineStyle.Kind]. */
        fun of(net: TransitNetwork): List<MapRoute> {
            val kinds = LineStyle.kindsOf(net)
            return net.routes.mapIndexed { i, r -> MapRoute(r.id, r.shortName, r.color, kinds[i]) }
        }
    }
}

/** Spherical Web Mercator (EPSG:3857), the projection MapLibre draws in. */
object WebMercator {
    const val R = 6_378_137.0
    fun x(lon: Double): Double = Math.toRadians(lon) * R
    fun y(lat: Double): Double = ln(tan(PI / 4 + Math.toRadians(lat) / 2)) * R
    fun lat(y: Double): Double = Math.toDegrees(2 * atan(exp(y / R)) - PI / 2)
    fun lon(x: Double): Double = Math.toDegrees(x / R)

    /** Web Mercator metres per dp at [zoom] (MapLibre: 512-dp tiles). */
    fun unitsPerDp(zoom: Double): Double = 2 * PI * R / (512.0 * 2.0.pow(zoom))

    /**
     * Ground metres -> Web Mercator metres at Nantes (latitude 47.22): every rule in metres on the
     * ground (hub radius, roundabout size) is scaled by this. One constant is enough over a network
     * 40 km across (the scale varies by 0.3 %).
     */
    val NANTES_SCALE: Double = 1 / kotlin.math.cos(Math.toRadians(47.22))
}

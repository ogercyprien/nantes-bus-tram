package com.bustramnantes.transit.tools

import com.bustramnantes.transit.RideShapes
import com.bustramnantes.transit.RouteMode
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.haversineMeters
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Builds the [RideShapes] of a network - the street path of every stop-to-stop hop - from the same
 * Geofabrik `.osm.pbf` extract as the walking graph. Build-time only (`:transit:buildSeed`).
 *
 *  - **bus graph**: roads a bus may use ([isBusWay]: incl. `highway=busway`, bus/psv-only roads), plus
 *    any way a Naolib bus route relation lists; one-way unless `oneway:bus|psv=no` ([busOneway]);
 *    roads with `construction=minor` (open during works) and streets with tram tracks in them count;
 *  - **tram graph**: `railway=tram|light_rail`, both ways;
 *  - **ferry graph**: `route=ferry` ways, both ways (the Navibus crossings: N1 Gare Maritime -
 *    Trentemoult drawn straight cut across Sainte-Anne); a crossing OSM doesn't map stays straight.
 *
 * Each hop is routed on its mode's [VehicleGraph], guided by the OSM route relations (`type=route`,
 * network Naolib/TAN, `ref` = the line's short name) of every line that runs that hop: their ways
 * cost 1×, others [VehicleGraph.OFF_RELATION_FACTOR]×. Lines without a relation (the 1xx school
 * lines, ...) are simply routed on the streets. A hop gets no entry - drawn straight - when a stop is
 * more than [VehicleGraph.MAX_SNAP_M] from the graph, no path exists, or the path is absurd (longer
 * than [MAX_DETOUR_RATIO] × crow-fly + [MAX_DETOUR_SLACK_M]). Paths are simplified to [SIMPLIFY_M].
 *
 * Measured on the October 2026 network: 2,875 hops, all but a handful routed; routed length median
 * 1.07 × crow-fly; ~82 % of it on the lines' own relations.
 */
object RideShapeBuilder {
    private const val MAX_DETOUR_RATIO = 2.5
    private const val MAX_DETOUR_SLACK_M = 300.0
    private const val SIMPLIFY_M = 1.5
    /** Relation member roles that are the route's ways ("route": 75's busway at Boisbonne). */
    private val RELATION_WAY_ROLES = setOf("", "forward", "backward", "route")

    // Box around the métropole, as in WalkGraphBuilder.
    private const val BOX_LAT_MIN = 46.95
    private const val BOX_LAT_MAX = 47.45
    private const val BOX_LON_MIN = -2.0
    private const val BOX_LON_MAX = -1.25

    private val BUS_HIGHWAYS = setOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
        "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street",
        "service", "road", "busway", "bus_guideway",
    )
    private val ALLOWED = setOf("yes", "designated", "permissive", "destination")

    fun isBusWay(t: Map<String, String>): Boolean {
        val hw = t["highway"] ?: return false
        val busOk = t["bus"] in ALLOWED || t["psv"] in ALLOWED
        if (hw !in BUS_HIGHWAYS && !((hw == "pedestrian" || hw == "cycleway" || hw == "footway") && busOk)) return false
        // `construction=minor`: works on a road that stays open (Bd Victor Hugo at Mangin, autumn 2026:
        // without it 3B had no path from Mangin to Pirmil). Any other construction value: closed.
        if (t["area"] == "yes" || hw == "construction" || (t["construction"] != null && t["construction"] != "minor")) return false
        val access = t["access"] ?: t["motor_vehicle"] ?: t["vehicle"]
        if ((access == "no" || access == "private") && !busOk) return false
        if ((t["bus"] == "no" || t["psv"] == "no") && t["bus"] !in ALLOWED) return false
        return true
    }

    fun isTramWay(t: Map<String, String>): Boolean = t["railway"] == "tram" || t["railway"] == "light_rail"

    /** 1 = forwards only, -1 = backwards only, 0 = both ways - for a bus. */
    fun busOneway(t: Map<String, String>): Int {
        if (t["oneway:bus"] == "no" || t["oneway:psv"] == "no") return 0
        if (t["highway"] == "busway" && t["oneway"] == null) return 0
        return when (t["oneway"]) {
            "yes", "1", "true" -> 1
            "-1", "reverse" -> -1
            "no" -> 0
            else -> if (t["junction"] == "roundabout" || t["junction"] == "circular" || t["highway"] == "motorway") 1 else 0
        }
    }

    /** A Naolib route relation (by the tags the extract carries for them). */
    fun isNetworkRoute(t: Map<String, String>): Boolean {
        if (t["type"] != "route" || t["route"] !in setOf("bus", "tram", "trolleybus", "light_rail")) return false
        val net = (t["network"].orEmpty() + " " + t["operator"].orEmpty()).lowercase()
        return "naolib" in net || "semitan" in net || "tan" in net.split(' ', ';', ',')
    }

    class Stats(val hops: Int, val routed: Int, val straight: Int, val lines: Int, val linesWithRelation: Int)

    fun build(network: TransitNetwork, pbf: File, log: (String) -> Unit = ::println): Pair<RideShapes, Stats> {
        val reader = OsmPbfReader(pbf)

        // 1. Route relations: way ids per line short name.
        val relationWays = HashMap<String, HashSet<Long>>()
        reader.read(object : OsmPbfReader.Visitor {
            override fun wantRelations() = true
            override fun relation(id: Long, tags: Map<String, String>, memberIds: LongArray, memberTypes: IntArray, roles: Array<String>) {
                if (!isNetworkRoute(tags)) return
                val ref = tags["ref"] ?: return
                val set = relationWays.getOrPut(ref) { HashSet() }
                for (i in memberIds.indices) {
                    // "route" is used by some members too (75's busway at Boisbonne); stops and platforms aren't ways.
                    if (memberTypes[i] == 1 && roles[i] in RELATION_WAY_ROLES) set += memberIds[i]
                }
            }
        })
        val allRelationWays = relationWays.values.flatten().toHashSet()

        // 2. Nodes in the box.
        val nodes = WalkGraphBuilder.NodeStore()
        val latMin = (BOX_LAT_MIN * 1e7).toInt(); val latMax = (BOX_LAT_MAX * 1e7).toInt()
        val lonMin = (BOX_LON_MIN * 1e7).toInt(); val lonMax = (BOX_LON_MAX * 1e7).toInt()
        reader.read(object : OsmPbfReader.Visitor {
            override fun wantNodes() = true
            override fun node(id: Long, lat7: Int, lon7: Int) {
                if (lat7 in latMin..latMax && lon7 in lonMin..lonMax) nodes.add(id, lat7, lon7)
            }
        })
        nodes.seal()

        // 3. Ways, split where they leave the box.
        val busWays = ArrayList<VehicleGraph.Way>(); val tramWays = ArrayList<VehicleGraph.Way>(); val ferryWays = ArrayList<VehicleGraph.Way>()
        reader.read(object : OsmPbfReader.Visitor {
            override fun wantWays() = true
            override fun way(id: Long, tags: Map<String, String>, refs: LongArray) {
                val tram = isTramWay(tags)
                // A way a route relation lists is taken even if the profile says no: the relation knows.
                // A street with tram tracks in it (`highway=*` + `railway=tram`) is in both graphs: buses
                // drive it too (Rue des Renards, 96 between Boissière and Santos Dumont).
                val bus = isBusWay(tags) || (id in allRelationWays && tags["highway"] != null)
                val ferry = tags["route"] == "ferry"
                if (!tram && !bus && !ferry) return
                for (run in insideRuns(refs, nodes)) {
                    fun way(ow: Int) = VehicleGraph.Way(
                        id, LongArray(run.size) { refs[run[it]] },
                        DoubleArray(run.size) { nodes.lat(nodes.indexOf(refs[run[it]])) / 1e7 },
                        DoubleArray(run.size) { nodes.lon(nodes.indexOf(refs[run[it]])) / 1e7 },
                        forward = ow >= 0, backward = ow <= 0,
                    )
                    if (tram) tramWays += way(0)
                    if (bus) busWays += way(busOneway(tags))
                    if (ferry) ferryWays += way(0)
                }
            }
        })
        val bus = VehicleGraph(busWays)
        val tram = VehicleGraph(tramWays)
        val ferry = VehicleGraph(ferryWays)
        log("bus graph: ${bus.nodeCount} nodes, ${bus.arcCount} arcs; tram graph: ${tram.nodeCount} nodes, ${tram.arcCount} arcs; ferry graph: ${ferry.nodeCount} nodes, ${ferry.arcCount} arcs")

        // 4. Hops: every consecutive quay pair, with its mode and the lines that run it.
        class Hop(val fromQuay: Int, val toQuay: Int, val mode: RouteMode, val lines: MutableSet<String>)
        val hops = LinkedHashMap<Long, Hop>()
        for (p in network.patterns) {
            val route = network.routes[p.route]
            for (k in 0 until p.quays.size - 1) {
                val a = p.quays[k]; val b = p.quays[k + 1]
                hops.getOrPut(RideShapes.key(network.quayNumber[a], network.quayNumber[b])) { Hop(a, b, route.mode, HashSet()) }.lines += route.shortName
            }
        }

        val preferredCache = HashMap<Pair<RouteMode, Set<String>>, Set<Int>>()
        val out = HashMap<Long, DoubleArray>()
        var straight = 0
        for ((key, hop) in hops) {
            val graph = when (hop.mode) {
                RouteMode.BUS -> bus
                RouteMode.TRAM -> tram
                RouteMode.FERRY -> ferry
            }
            val preferred = preferredCache.getOrPut(hop.mode to hop.lines) {
                graph.waysWithIds(hop.lines.flatMapTo(HashSet()) { relationWays[it].orEmpty() })
            }
            val la1 = network.quayLat[hop.fromQuay]; val lo1 = network.quayLon[hop.fromQuay]
            val la2 = network.quayLat[hop.toQuay]; val lo2 = network.quayLon[hop.toQuay]
            val crow = haversineMeters(la1, lo1, la2, lo2)
            val maxLength = crow * MAX_DETOUR_RATIO + MAX_DETOUR_SLACK_M
            val sa = graph.snap(la1, lo1, preferred)
            val sb = graph.snap(la2, lo2, preferred)
            val path = if (sa != null && sb != null) graph.route(sa, sb, preferred, maxLength * VehicleGraph.OFF_RELATION_FACTOR) else null
            val full = path?.let { listOf(doubleArrayOf(la1, lo1)) + it + listOf(doubleArrayOf(la2, lo2)) }
            if (full == null || length(full) > maxLength) { straight++; continue }
            val simplified = simplify(full, SIMPLIFY_M)
            val interior = simplified.subList(1, simplified.size - 1)
            out[key] = DoubleArray(interior.size * 2) { if (it % 2 == 0) interior[it / 2][0] else interior[it / 2][1] }
        }
        val lines = network.routes.filter { it.mode != RouteMode.FERRY }.map { it.shortName }
        val stats = Stats(hops.size, out.size, straight, lines.size, lines.count { it in relationWays })
        return RideShapes.of(out) to stats
    }

    /** Runs of consecutive way-node positions whose node is inside the box (a way may leave and re-enter it). */
    private fun insideRuns(refs: LongArray, nodes: WalkGraphBuilder.NodeStore): List<IntArray> {
        val runs = ArrayList<IntArray>()
        var cur = ArrayList<Int>()
        for (k in refs.indices) {
            if (nodes.indexOf(refs[k]) >= 0) cur += k
            else { if (cur.size >= 2) runs += cur.toIntArray(); cur = ArrayList() }
        }
        if (cur.size >= 2) runs += cur.toIntArray()
        return runs
    }

    private fun length(p: List<DoubleArray>) = (0 until p.size - 1).sumOf { haversineMeters(p[it][0], p[it][1], p[it + 1][0], p[it + 1][1]) }

    /** Douglas–Peucker in local metres; keeps both ends. */
    internal fun simplify(p: List<DoubleArray>, tolerance: Double): List<DoubleArray> {
        if (p.size < 3) return p
        val keep = BooleanArray(p.size).also { it[0] = true; it[p.size - 1] = true }
        val kx = cos(Math.toRadians(p[0][0])) * 111_320.0; val ky = 110_540.0
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to p.size - 1) }
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            if (j <= i + 1) continue
            val ax = p[i][1] * kx; val ay = p[i][0] * ky; val dx = p[j][1] * kx - ax; val dy = p[j][0] * ky - ay
            val l2 = dx * dx + dy * dy
            var worst = -1.0; var wi = -1
            for (k in i + 1 until j) {
                val px = p[k][1] * kx - ax; val py = p[k][0] * ky - ay
                val t = if (l2 == 0.0) 0.0 else ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0)
                val ex = t * dx - px; val ey = t * dy - py
                val d = sqrt(ex * ex + ey * ey)
                if (d > worst) { worst = d; wi = k }
            }
            if (worst > tolerance) { keep[wi] = true; stack.add(i to wi); stack.add(wi to j) }
        }
        return p.filterIndexed { i, _ -> keep[i] }
    }
}

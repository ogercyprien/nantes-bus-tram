package com.bustramnantes.transit.walk

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pedestrian street network of Nantes Métropole, from OpenStreetMap (Geofabrik extract, built by
 * `./gradlew :transit:packWalkGraph`, shipped as `assets/seed/walk_graph.bin`, see [WalkGraphFormat]).
 *
 * Vertices are way junctions and dead ends; an edge is the stretch of one way between two vertices,
 * with its walked length in metres and its intermediate shape points (simplified to ~2 m) for
 * drawing. Undirected (pedestrians ignore one-way streets). Coordinates are micro-degrees.
 *
 * Outside the métropole (or > [MAX_SNAP_METERS] from any walkable way) nothing snaps, and callers fall
 * back to the crow-fly estimate - by design (real walks inside the métropole,
 * straight lines are good enough outside).
 *
 * Queries:
 *  - [snapAll] a point to its nearest edges (or [snap] to the nearest one);
 *  - [search] from them: Dijkstra up to a walked distance, then [WalkSearch.distanceTo] any other
 *    attached point and [WalkSearch.pathTo] for its geometry.
 */
class WalkGraph(
    /** Free text describing the OSM extract the graph was built from (e.g. "pays-de-la-loire-261011"). */
    val source: String,
    val vertexLat: IntArray,
    val vertexLon: IntArray,
    val edgeFrom: IntArray,
    val edgeTo: IntArray,
    /** Walked length, metres (measured on the full OSM geometry, before simplification). */
    val edgeLength: FloatArray,
    /** Intermediate points of edge e: indices [geomStart[e], geomStart[e+1]) of [geomLat]/[geomLon]. */
    val geomStart: IntArray,
    val geomLat: IntArray,
    val geomLon: IntArray,
) {
    val vertexCount: Int get() = vertexLat.size
    val edgeCount: Int get() = edgeFrom.size

    /** CSR adjacency: edges of vertex v are adjEdge[adjStart[v] until adjStart[v+1]]. */
    private val adjStart = IntArray(vertexCount + 1)
    private val adjEdge = IntArray(edgeCount * 2)

    init {
        for (e in 0 until edgeCount) { adjStart[edgeFrom[e] + 1]++; adjStart[edgeTo[e] + 1]++ }
        for (v in 0 until vertexCount) adjStart[v + 1] += adjStart[v]
        val fill = adjStart.copyOf(vertexCount)
        for (e in 0 until edgeCount) { adjEdge[fill[edgeFrom[e]]++] = e; adjEdge[fill[edgeTo[e]]++] = e }
    }

    // ------------------------------------------------------------------ geometry helpers

    /** Full polyline of edge e (from-vertex, intermediate points, to-vertex), lat/lon micro-degrees interleaved. */
    fun edgePolyline(e: Int): IntArray {
        val a = geomStart[e]; val b = geomStart[e + 1]
        val out = IntArray((b - a + 2) * 2)
        out[0] = vertexLat[edgeFrom[e]]; out[1] = vertexLon[edgeFrom[e]]
        var k = 2
        for (i in a until b) { out[k++] = geomLat[i]; out[k++] = geomLon[i] }
        out[k++] = vertexLat[edgeTo[e]]; out[k] = vertexLon[edgeTo[e]]
        return out
    }

    // ------------------------------------------------------------------ spatial index

    private val cellLat = CELL_DEGREES_LAT
    private val cellLon = CELL_DEGREES_LAT / cos(Math.toRadians(47.2))
    private val grid: Map<Long, IntArray> by lazy { buildGrid() }

    private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun buildGrid(): Map<Long, IntArray> {
        val lists = HashMap<Long, IntList>()
        for (e in 0 until edgeCount) {
            val p = edgePolyline(e)
            var i = 0
            while (i + 3 < p.size) {
                val la1 = p[i] / 1e6; val lo1 = p[i + 1] / 1e6; val la2 = p[i + 2] / 1e6; val lo2 = p[i + 3] / 1e6
                val x0 = floor(min(la1, la2) / cellLat).toInt(); val x1 = floor(max(la1, la2) / cellLat).toInt()
                val y0 = floor(min(lo1, lo2) / cellLon).toInt(); val y1 = floor(max(lo1, lo2) / cellLon).toInt()
                for (x in x0..x1) for (y in y0..y1) {
                    val l = lists.getOrPut(cellKey(x, y)) { IntList() }
                    if (l.size == 0 || l.last() != e) l.add(e)
                }
                i += 2
            }
        }
        val out = HashMap<Long, IntArray>(lists.size * 2)
        for ((k, v) in lists) out[k] = v.toArray()
        return out
    }

    /**
     * The nearest point of the network to (lat, lon), within [maxMeters]; null outside the covered
     * area. [Snap.along] is measured from the edge's from-vertex in walked metres.
     */
    fun snap(lat: Double, lon: Double, maxMeters: Double = MAX_SNAP_METERS): Snap? = snapAll(lat, lon, maxMeters).firstOrNull()

    /**
     * Every edge passing within [ATTACH_SLACK_METERS] of the nearest one (at most [MAX_ATTACHMENTS],
     * nearest first): a point is attached to all of them, so a search isn't trapped on the single
     * nearest way when that one is a dead-end path or lies behind a fence - the street a few metres
     * further may be the real way out. Empty outside the covered area.
     */
    fun snapAll(lat: Double, lon: Double, maxMeters: Double = MAX_SNAP_METERS): List<Snap> {
        val cx = floor(lat / cellLat).toInt(); val cy = floor(lon / cellLon).toInt()
        val reach = (maxMeters / (cellLat * 111_000.0)).toInt() + 1
        val mLat = 111_195.0
        val mLon = 111_195.0 * cos(Math.toRadians(lat))
        val found = ArrayList<Snap>()
        val seen = HashSet<Int>()
        for (dx in -reach..reach) for (dy in -reach..reach) {
            val cell = grid[cellKey(cx + dx, cy + dy)] ?: continue
            for (e in cell) {
                if (!seen.add(e)) continue
                val p = edgePolyline(e)
                // Walk the polyline in a local metric frame centred on the query point.
                var polyAlong = 0.0
                var bestAlongPoly = -1.0
                var bestPointLat = 0.0
                var bestPointLon = 0.0
                var edgeBestD2 = Double.MAX_VALUE
                var i = 0
                while (i + 3 < p.size) {
                    val ax = (p[i] / 1e6 - lat) * mLat; val ay = (p[i + 1] / 1e6 - lon) * mLon
                    val bx = (p[i + 2] / 1e6 - lat) * mLat; val by = (p[i + 3] / 1e6 - lon) * mLon
                    val sx = bx - ax; val sy = by - ay
                    val segLen2 = sx * sx + sy * sy
                    val t = if (segLen2 == 0.0) 0.0 else ((-ax * sx - ay * sy) / segLen2).coerceIn(0.0, 1.0)
                    val px = ax + t * sx; val py = ay + t * sy
                    val d2 = px * px + py * py
                    val segLen = sqrt(segLen2)
                    if (d2 < edgeBestD2) {
                        edgeBestD2 = d2
                        bestAlongPoly = polyAlong + t * segLen
                        bestPointLat = lat + px / mLat
                        bestPointLon = lon + py / mLon
                    }
                    polyAlong += segLen
                    i += 2
                }
                if (edgeBestD2 <= maxMeters * maxMeters) {
                    val scale = if (polyAlong > 0) edgeLength[e] / polyAlong else 0.0
                    found += Snap(e, (bestAlongPoly * scale).toFloat(), sqrt(edgeBestD2).toFloat(), bestPointLat, bestPointLon, lat, lon)
                }
            }
        }
        if (found.isEmpty()) return found
        found.sortBy { it.lateral }
        val limit = found[0].lateral + ATTACH_SLACK_METERS
        return found.filter { it.lateral <= limit }.take(MAX_ATTACHMENTS)
    }

    /**
     * Dijkstra from [origin], settling vertices up to [maxMeters] walked (snap offsets included).
     * Passing a [workspace] avoids allocating per-vertex arrays for each search (many short searches,
     * e.g. the quay-to-quay footpaths); a search's answers are only valid until its workspace is reused.
     */
    fun search(origin: Snap, maxMeters: Double, workspace: WalkWorkspace = WalkWorkspace(vertexCount)): WalkSearch =
        WalkSearch(this, listOf(origin), maxMeters.toFloat(), workspace)

    /** [search] from a point attached to several edges ([snapAll]). */
    fun search(origins: List<Snap>, maxMeters: Double, workspace: WalkWorkspace = WalkWorkspace(vertexCount)): WalkSearch =
        WalkSearch(this, origins, maxMeters.toFloat(), workspace)

    internal fun adjacency(v: Int, i: Int): Int = adjEdge[i]
    internal fun adjRange(v: Int): IntRange = adjStart[v] until adjStart[v + 1]

    companion object {
        /** A point farther than this from every walkable way is "outside the walking network". */
        const val MAX_SNAP_METERS = 200.0
        const val ATTACH_SLACK_METERS = 40f
        const val MAX_ATTACHMENTS = 6
        private const val CELL_DEGREES_LAT = 0.001 // ~111 m
    }
}

/**
 * A point attached to the network: on edge [edge], [along] walked metres from its from-vertex,
 * [lateral] metres from the original point ([originLat]/[originLon]) to [lat]/[lon] on the edge.
 */
data class Snap(
    val edge: Int,
    val along: Float,
    val lateral: Float,
    val lat: Double,
    val lon: Double,
    val originLat: Double,
    val originLon: Double,
)

/** Reusable per-vertex arrays for [WalkGraph.search]; only the vertices a search touched are reset. */
class WalkWorkspace(vertexCount: Int) {
    internal val dist = FloatArray(vertexCount) { Float.POSITIVE_INFINITY }
    internal val predEdge = IntArray(vertexCount) { -1 }
    private val touched = IntList()

    internal fun reset() {
        for (i in 0 until touched.size) { val v = touched[i]; dist[v] = Float.POSITIVE_INFINITY; predEdge[v] = -1 }
        touched.clear()
    }

    internal fun touch(v: Int) = touched.add(v)
}

/** The result of one [WalkGraph.search]. */
class WalkSearch internal constructor(
    private val g: WalkGraph,
    private val origins: List<Snap>,
    private val limit: Float,
    workspace: WalkWorkspace,
) {
    private val dist = workspace.dist
    private val predEdge = workspace.predEdge

    /** Seed vertex -> the origin attachment it was seeded from (paths start there). */
    private val seedOrigin = HashMap<Int, Int>()

    init {
        workspace.reset()
        val heap = MinHeap()
        origins.forEachIndexed { k, origin ->
            val e = origin.edge
            val a = g.edgeFrom[e]; val b = g.edgeTo[e]
            val da = origin.lateral + origin.along
            val db = origin.lateral + (g.edgeLength[e] - origin.along)
            if (da < dist[a]) { if (dist[a] == Float.POSITIVE_INFINITY) workspace.touch(a); dist[a] = da; seedOrigin[a] = k; heap.push(da, a) }
            if (db < dist[b]) { if (dist[b] == Float.POSITIVE_INFINITY) workspace.touch(b); dist[b] = db; seedOrigin[b] = k; heap.push(db, b) }
        }
        while (heap.isNotEmpty()) {
            val d = heap.peekKey(); val v = heap.pop()
            if (d > dist[v] || d > limit) continue
            for (i in g.adjRange(v)) {
                val edge = g.adjacency(v, i)
                val w = if (g.edgeFrom[edge] == v) g.edgeTo[edge] else g.edgeFrom[edge]
                val nd = d + g.edgeLength[edge]
                if (nd < dist[w] && nd <= limit) {
                    if (dist[w] == Float.POSITIVE_INFINITY) workspace.touch(w)
                    dist[w] = nd; predEdge[w] = edge; heap.push(nd, w)
                }
            }
        }
    }

    /** Walked metres from the origin point to [target]'s original point, or null if beyond the limit. */
    fun distanceTo(target: Snap): Double? = best(target)?.distance?.toDouble()

    /** The shortest of the walks to a point attached to several edges ([WalkGraph.snapAll]). */
    fun distanceTo(targets: List<Snap>): Double? = targets.mapNotNull { distanceTo(it) }.minOrNull()

    private class Best(val distance: Float, val via: Int, val origin: Int)

    /**
     * The best way to reach [target]: [Best.via] = -1 for a walk along an edge shared with origin
     * attachment [Best.origin], else the vertex through which the path reaches the target's edge.
     */
    private fun best(target: Snap): Best? {
        val e = target.edge
        val a = g.edgeFrom[e]; val b = g.edgeTo[e]
        var bestD = Float.POSITIVE_INFINITY
        var via = -2
        var originIndex = -1
        origins.forEachIndexed { k, origin ->
            if (e == origin.edge) {
                val d = origin.lateral + abs(origin.along - target.along) + target.lateral
                if (d < bestD) { bestD = d; via = -1; originIndex = k }
            }
        }
        val viaA = dist[a] + target.along + target.lateral
        if (viaA < bestD) { bestD = viaA; via = a }
        val viaB = dist[b] + (g.edgeLength[e] - target.along) + target.lateral
        if (viaB < bestD) { bestD = viaB; via = b }
        return if (bestD <= limit && via != -2) Best(bestD, via, originIndex) else null
    }

    /** [pathTo] the nearest (walked) of several attachments of one point. */
    fun pathTo(targets: List<Snap>): List<DoubleArray>? = targets.minByOrNull { distanceTo(it) ?: Double.MAX_VALUE }?.let(::pathTo)

    /**
     * The walk to [target] as a polyline (lat, lon pairs in degrees): origin point, the street path,
     * target point. Null if [target] is beyond the limit.
     */
    fun pathTo(target: Snap): List<DoubleArray>? {
        val best = best(target) ?: return null
        val out = ArrayList<DoubleArray>()
        if (best.via == -1) {
            val origin = origins[best.origin]
            out += doubleArrayOf(origin.originLat, origin.originLon)
            out += doubleArrayOf(origin.lat, origin.lon)
            out.addAll(partial(origin.edge, origin.along, target.along))
        } else {
            // Edges from the target side back to a seed vertex (an end of an origin attachment's edge).
            val chain = ArrayList<Int>()
            var v = best.via
            while (predEdge[v] >= 0) {
                val e = predEdge[v]
                chain += e
                v = if (g.edgeFrom[e] == v) g.edgeTo[e] else g.edgeFrom[e]
            }
            val origin = origins[seedOrigin[v] ?: 0]
            out += doubleArrayOf(origin.originLat, origin.originLon)
            out += doubleArrayOf(origin.lat, origin.lon)
            val oe = origin.edge
            out.addAll(partial(oe, origin.along, if (v == g.edgeFrom[oe]) 0f else g.edgeLength[oe]))
            var at = v
            for (e in chain.asReversed()) {
                val forward = g.edgeFrom[e] == at
                val poly = g.edgePolyline(e)
                val pts = (0 until poly.size / 2).map { doubleArrayOf(poly[it * 2] / 1e6, poly[it * 2 + 1] / 1e6) }
                out.addAll(if (forward) pts else pts.asReversed())
                at = if (forward) g.edgeTo[e] else g.edgeFrom[e]
            }
            val te = target.edge
            out.addAll(partial(te, if (at == g.edgeFrom[te]) 0f else g.edgeLength[te], target.along))
        }
        out += doubleArrayOf(target.lat, target.lon)
        out += doubleArrayOf(target.originLat, target.originLon)
        return dedupe(out)
    }

    /** Points of edge [e] between walked positions [from] and [to] (either order). */
    private fun partial(e: Int, from: Float, to: Float): List<DoubleArray> {
        val poly = g.edgePolyline(e)
        val n = poly.size / 2
        val cum = DoubleArray(n)
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + approxMeters(poly[i * 2 - 2], poly[i * 2 - 1], poly[i * 2], poly[i * 2 + 1])
        }
        val scale = if (cum[n - 1] > 0) cum[n - 1] / g.edgeLength[e] else 0.0
        val lo = min(from, to) * scale; val hi = max(from, to) * scale
        val pts = (0 until n).filter { cum[it] > lo && cum[it] < hi }.map { doubleArrayOf(poly[it * 2] / 1e6, poly[it * 2 + 1] / 1e6) }
        return if (from <= to) pts else pts.asReversed()
    }

    private fun dedupe(points: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(points.size)
        for (p in points) if (out.isEmpty() || out.last()[0] != p[0] || out.last()[1] != p[1]) out += p
        return out
    }
}

internal fun approxMeters(lat1: Int, lon1: Int, lat2: Int, lon2: Int): Double {
    val dLat = (lat2 - lat1) / 1e6 * 111_195.0
    val dLon = (lon2 - lon1) / 1e6 * 111_195.0 * cos(Math.toRadians(lat1 / 1e6))
    return sqrt(dLat * dLat + dLon * dLon)
}

/** Binary min-heap of (float key, int value); duplicates allowed (lazy deletion by the caller). */
internal class MinHeap {
    private var keys = FloatArray(256)
    private var vals = IntArray(256)
    private var size = 0

    fun isNotEmpty() = size > 0
    fun peekKey(): Float = keys[0]

    fun push(k: Float, v: Int) {
        if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val parent = (i - 1) shr 1
            if (keys[parent] <= k) break
            keys[i] = keys[parent]; vals[i] = vals[parent]; i = parent
        }
        keys[i] = k; vals[i] = v
    }

    fun pop(): Int {
        val top = vals[0]
        val k = keys[--size]; val v = vals[size]
        var i = 0
        while (true) {
            var c = i * 2 + 1
            if (c >= size) break
            if (c + 1 < size && keys[c + 1] < keys[c]) c++
            if (keys[c] >= k) break
            keys[i] = keys[c]; vals[i] = vals[c]; i = c
        }
        if (size > 0) { keys[i] = k; vals[i] = v }
        return top
    }
}

/** Growable int list (no boxing). */
internal class IntList {
    private var a = IntArray(4)
    var size = 0
        private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun last(): Int = a[size - 1]
    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(size)
    fun clear() { size = 0 }
}

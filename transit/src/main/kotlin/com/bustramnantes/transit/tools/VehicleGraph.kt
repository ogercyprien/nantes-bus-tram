package com.bustramnantes.transit.tools

import com.bustramnantes.transit.haversineMeters
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A directed graph over OSM nodes for one vehicle profile (bus or tram), build-time only
 * ([RideShapeBuilder]): routes a vehicle between two stops along the streets / tracks.
 *
 * Stops are **projected onto the nearest segment** (not the nearest node: a stop in the middle of a
 * long straight way must not detour to a junction), preferring a way of the line's own OSM route
 * relation when it is nearly as close ([PREFERRED_SNAP_SLACK_M]) - at a stop served from a bus lane
 * beside the main carriageway, the lane is the right one.
 *
 * Costs: metres, × [OFF_RELATION_FACTOR] off the preferred ways - so a complete route relation is
 * followed exactly, and a gap in it (or a line with no relation at all) is bridged by the shortest
 * legal path. One-way ways are only travelled forwards.
 */
class VehicleGraph(ways: List<Way>) {

    /** An OSM way: its nodes' coordinates (degrees) and which directions the vehicle may use. */
    class Way(val id: Long, val nodeIds: LongArray, val lat: DoubleArray, val lon: DoubleArray, val forward: Boolean, val backward: Boolean)

    private val nodeLat: DoubleArray
    private val nodeLon: DoubleArray
    private val first: IntArray
    private val to: IntArray
    private val len: FloatArray
    private val arcWay: IntArray
    private val segA: IntArray
    private val segB: IntArray
    private val segWay: IntArray
    private val segFwd: BooleanArray
    private val segBwd: BooleanArray
    private val wayIds: LongArray = LongArray(ways.size) { ways[it].id }
    private val grid = HashMap<Long, IntArray>()

    val nodeCount: Int get() = nodeLat.size
    val arcCount: Int get() = to.size

    init {
        val index = HashMap<Long, Int>()
        val la = DoubleList(); val lo = DoubleList()
        val aFrom = IntList(); val aTo = IntList(); val aWay = IntList(); val aLen = FloatList()
        val sA = IntList(); val sB = IntList(); val sW = IntList(); val sF = ArrayList<Boolean>(); val sBw = ArrayList<Boolean>()
        for ((wi, w) in ways.withIndex()) {
            fun node(k: Int) = index.getOrPut(w.nodeIds[k]) { la.add(w.lat[k]); lo.add(w.lon[k]); la.size - 1 }
            for (k in 0 until w.nodeIds.size - 1) {
                val a = node(k); val b = node(k + 1)
                if (a == b) continue
                val d = haversineMeters(la[a], lo[a], la[b], lo[b]).toFloat()
                if (w.forward) { aFrom.add(a); aTo.add(b); aWay.add(wi); aLen.add(d) }
                if (w.backward) { aFrom.add(b); aTo.add(a); aWay.add(wi); aLen.add(d) }
                sA.add(a); sB.add(b); sW.add(wi); sF += w.forward; sBw += w.backward
            }
        }
        nodeLat = la.toArray(); nodeLon = lo.toArray()
        val n = nodeLat.size
        first = IntArray(n + 1)
        for (k in 0 until aFrom.size) first[aFrom[k] + 1]++
        for (i in 0 until n) first[i + 1] += first[i]
        val fill = first.copyOf()
        to = IntArray(aFrom.size); len = FloatArray(aFrom.size); arcWay = IntArray(aFrom.size)
        for (k in 0 until aFrom.size) { val p = fill[aFrom[k]]++; to[p] = aTo[k]; len[p] = aLen[k]; arcWay[p] = aWay[k] }
        segA = sA.toArray(); segB = sB.toArray(); segWay = sW.toArray(); segFwd = sF.toBooleanArray(); segBwd = sBw.toBooleanArray()

        val cells = HashMap<Long, IntList>()
        for (s in segA.indices) {
            val i0 = cell(minOf(nodeLat[segA[s]], nodeLat[segB[s]])); val i1 = cell(maxOf(nodeLat[segA[s]], nodeLat[segB[s]]))
            val j0 = cell(minOf(nodeLon[segA[s]], nodeLon[segB[s]])); val j1 = cell(maxOf(nodeLon[segA[s]], nodeLon[segB[s]]))
            for (i in i0..i1) for (j in j0..j1) cells.getOrPut(cellKey(i, j)) { IntList() }.add(s)
        }
        for ((k, v) in cells) grid[k] = v.toArray()
    }

    /** Indices of the ways whose OSM id is in [osmIds] - the "preferred" set for [route]. */
    fun waysWithIds(osmIds: Set<Long>): Set<Int> = wayIds.indices.filterTo(HashSet()) { wayIds[it] in osmIds }

    /** A point projected on segment [seg], at fraction [t] from its first node. */
    class Snap(val seg: Int, val t: Double, val lat: Double, val lon: Double, val distance: Double)

    /** [lat]/[lon] projected on the graph within [MAX_SNAP_M], or null. */
    fun snap(lat: Double, lon: Double, preferred: Set<Int>): Snap? {
        var best: Snap? = null
        var bestPreferred: Snap? = null
        val r = (MAX_SNAP_M / 111_000.0 / CELL_DEG).toInt() + 1
        val ci = cell(lat); val cj = cell(lon)
        val seen = HashSet<Int>()
        for (i in ci - r..ci + r) for (j in cj - 2 * r..cj + 2 * r) {
            val segs = grid[cellKey(i, j)] ?: continue
            for (s in segs) {
                if (!seen.add(s)) continue
                val p = project(s, lat, lon)
                if (p.distance > MAX_SNAP_M) continue
                if (best == null || p.distance < best.distance) best = p
                if (segWay[s] in preferred && (bestPreferred == null || p.distance < bestPreferred.distance)) bestPreferred = p
            }
        }
        return if (bestPreferred != null && best != null && bestPreferred.distance <= best.distance + PREFERRED_SNAP_SLACK_M) bestPreferred else best
    }

    private fun project(s: Int, lat: Double, lon: Double): Snap {
        val kx = cos(Math.toRadians(lat)) * 111_320.0; val ky = 110_540.0
        val ax = (nodeLon[segA[s]] - lon) * kx; val ay = (nodeLat[segA[s]] - lat) * ky
        val bx = (nodeLon[segB[s]] - lon) * kx; val by = (nodeLat[segB[s]] - lat) * ky
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return Snap(s, t, lat + py / ky, lon + px / kx, sqrt(px * px + py * py))
    }

    // Dijkstra state, reused across searches (only the touched nodes are reset).
    private val dist = DoubleArray(nodeLat.size) { Double.MAX_VALUE }
    private val prev = IntArray(nodeLat.size) { -1 }
    private val touched = IntList()

    /**
     * The cheapest legal path from [a] to [b], as (lat, lon) points from [a]'s projection to [b]'s,
     * or null when there is none costing at most [maxCost].
     */
    fun route(a: Snap, b: Snap, preferred: Set<Int>, maxCost: Double): List<DoubleArray>? {
        fun factor(w: Int) = if (w in preferred) 1.0 else OFF_RELATION_FACTOR
        val sa = a.seg; val sb = b.seg
        // Same segment, target ahead in a direction the vehicle may take: straight along it.
        if (sa == sb && ((b.t >= a.t && segFwd[sa]) || (b.t <= a.t && segBwd[sa]))) {
            return listOf(doubleArrayOf(a.lat, a.lon), doubleArrayOf(b.lat, b.lon))
        }
        for (k in 0 until touched.size) { dist[touched[k]] = Double.MAX_VALUE; prev[touched[k]] = -1 }
        touched.clear()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun relax(v: Int, d: Double, p: Int) {
            if (d >= dist[v]) return
            if (dist[v] == Double.MAX_VALUE) touched.add(v)
            dist[v] = d; prev[v] = p; queue.add(d to v)
        }
        val lenA = segmentLength(sa) * factor(segWay[sa])
        val lenB = segmentLength(sb) * factor(segWay[sb])
        if (segFwd[sa]) relax(segB[sa], (1 - a.t) * lenA, -1)
        if (segBwd[sa]) relax(segA[sa], a.t * lenA, -1)
        // The target is entered from its segment's first node going forwards, or its second going backwards.
        val enterAtA = if (segFwd[sb]) b.t * lenB else Double.NaN
        val enterAtB = if (segBwd[sb]) (1 - b.t) * lenB else Double.NaN
        var best = Double.MAX_VALUE
        var bestNode = -1
        while (queue.isNotEmpty()) {
            val (d, u) = queue.poll()
            if (d > dist[u]) continue
            if (d >= best || d > maxCost) break
            if (u == segA[sb] && !enterAtA.isNaN() && d + enterAtA < best) { best = d + enterAtA; bestNode = u }
            if (u == segB[sb] && !enterAtB.isNaN() && d + enterAtB < best) { best = d + enterAtB; bestNode = u }
            for (e in first[u] until first[u + 1]) relax(to[e], d + len[e] * factor(arcWay[e]), u)
        }
        if (bestNode < 0 || best > maxCost) return null
        val nodes = ArrayList<Int>()
        var v = bestNode
        while (v >= 0) { nodes += v; v = prev[v] }
        nodes.reverse()
        val out = ArrayList<DoubleArray>(nodes.size + 2)
        out += doubleArrayOf(a.lat, a.lon)
        for (n in nodes) out += doubleArrayOf(nodeLat[n], nodeLon[n])
        out += doubleArrayOf(b.lat, b.lon)
        return out
    }

    private fun segmentLength(s: Int) = haversineMeters(nodeLat[segA[s]], nodeLon[segA[s]], nodeLat[segB[s]], nodeLon[segB[s]])

    private fun cell(deg: Double) = floor(deg / CELL_DEG).toInt()
    private fun cellKey(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

    companion object {
        const val OFF_RELATION_FACTOR = 1.6
        const val MAX_SNAP_M = 80.0
        const val PREFERRED_SNAP_SLACK_M = 15.0
        private const val CELL_DEG = 0.001
    }
}

private class IntList {
    var a = IntArray(16); var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray() = a.copyOf(size)
}

private class FloatList {
    var a = FloatArray(16); var size = 0
    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
}

private class DoubleList {
    var a = DoubleArray(16); var size = 0
    fun add(v: Double) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray() = a.copyOf(size)
}

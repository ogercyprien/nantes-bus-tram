package com.bustramnantes.transit.walk

import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.haversineMeters
import kotlin.math.cos

/**
 * Walking transfers between quays, precomputed once per network and stored in `network.bin`
 * (format v2): for each quay, the quays reachable on foot and the walked metres.
 *
 * Inside the walking network ([compute]): the street distance on the [WalkGraph] (so a quay across
 * the Loire or the railway isn't "next door"), kept when ≤ [MAX_WALK_METERS] - the walk a crow-fly
 * model allows for [MAX_CROW_METERS] (500 m × 1.3). Candidates are the quays ≤ [MAX_CROW_METERS] crow-fly apart.
 * Two exceptions keep crow-fly × [CROW_DETOUR]: a quay that doesn't snap onto the graph (outside the
 * métropole), and pairs ≤ [SAME_PLACE_METERS] apart that the graph can't connect (mapping gaps around
 * platforms - a 50 m walk exists in practice).
 *
 * [crowFly] is the graph-less model (crow-fly × 1.3 for every pair ≤ 500 m) - used for networks
 * packed without a graph and as the router's fallback.
 */
class Footpaths(
    /** Footpaths of quay q are indices [start[q], start[q+1]) of [to]/[meters]. */
    val start: IntArray,
    val to: IntArray,
    val meters: IntArray,
) {
    val pairCount: Int get() = to.size
    fun of(quay: Int): IntRange = start[quay] until start[quay + 1]

    companion object {
        const val MAX_CROW_METERS = 500.0
        const val MAX_WALK_METERS = 650.0
        const val SAME_PLACE_METERS = 50.0
        const val CROW_DETOUR = 1.3

        /** Quays farther than this from a walkable way count as off the graph (crow-fly for them). */
        const val QUAY_SNAP_METERS = 100.0

        fun crowFly(network: TransitNetwork): Footpaths = build(network) { _, _, crow -> (crow * CROW_DETOUR).toInt() }

        fun compute(network: TransitNetwork, graph: WalkGraph): Footpaths {
            val snaps = Array(network.quayCount) { graph.snapAll(network.quayLat[it], network.quayLon[it], QUAY_SNAP_METERS) }
            val workspace = WalkWorkspace(graph.vertexCount)
            var current = -1
            var search: WalkSearch? = null
            return build(network) { q, q2, crow ->
                val a = snaps[q]
                val b = snaps[q2]
                if (a.isEmpty() || b.isEmpty()) return@build (crow * CROW_DETOUR).toInt()
                if (current != q) { current = q; search = graph.search(a, MAX_WALK_METERS, workspace) }
                val walked = search!!.distanceTo(b)
                when {
                    walked != null -> walked.toInt()
                    crow <= SAME_PLACE_METERS -> (crow * CROW_DETOUR).toInt()
                    else -> null
                }
            }
        }

        /** Pairs ≤ [MAX_CROW_METERS] apart (grid-bucketed), costed by [cost] (null = no footpath). */
        private fun build(network: TransitNetwork, cost: (q: Int, q2: Int, crow: Double) -> Int?): Footpaths {
            val nq = network.quayCount
            val cellLat = MAX_CROW_METERS / 111_000.0
            val cellLon = cellLat / cos(Math.toRadians(47.2))
            val grid = HashMap<Long, MutableList<Int>>()
            fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFF)
            for (q in 0 until nq) {
                val x = (network.quayLat[q] / cellLat).toInt(); val y = (network.quayLon[q] / cellLon).toInt()
                grid.getOrPut(key(x, y)) { ArrayList() } += q
            }
            val start = IntArray(nq + 1)
            val to = IntList()
            val meters = IntList()
            for (q in 0 until nq) {
                val x = (network.quayLat[q] / cellLat).toInt(); val y = (network.quayLon[q] / cellLon).toInt()
                for (dx in -1..1) for (dy in -1..1) {
                    for (q2 in grid[key(x + dx, y + dy)] ?: continue) {
                        if (q2 == q) continue
                        val crow = haversineMeters(network.quayLat[q], network.quayLon[q], network.quayLat[q2], network.quayLon[q2])
                        if (crow > MAX_CROW_METERS) continue
                        val m = cost(q, q2, crow) ?: continue
                        if (m > MAX_WALK_METERS) continue
                        to.add(q2); meters.add(m)
                    }
                }
                start[q + 1] = to.size
            }
            return Footpaths(start, to.toArray(), meters.toArray())
        }
    }
}

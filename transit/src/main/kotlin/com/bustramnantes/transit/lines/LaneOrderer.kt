package com.bustramnantes.transit.lines

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lane order per segment, minimising crossings (LOOM's "line ordering" step, Bast, Brosi & Storandt).
 *
 * At a junction every segment end has its lanes on a circle around the node (ends sorted by angle,
 * lanes right to left looking out along the segment); each line through the node is a chord between
 * its two lanes, and two lines cross where their chords do. A crossing of two lines between the same
 * two segments weighs 2 (they braid: swapping them on one segment removes it), any other 1 (one line
 * crosses another to split off, often unavoidable).
 *
 * Optimal ordering is NP-hard; the search here is: [barycentre] sweeps (as in layered-graph drawing)
 * for a good start, then [optimise], a local search (every permutation of segments up to 6 lines,
 * single-lane moves for wider ones) until nothing improves. On the Nantes network this leaves about
 * 250 crossings at z12 and 410 at z15 (`LineMapRegressionTest` holds those budgets).
 *
 * [pinned]: segments whose order is constrained by hand, applied to every order tried (the Pirmil
 * adjustment, [LineMapBuilder.TRAMS_WEST_HUBS]). A pin function only reorders, so the search still
 * places the unpinned lines around the pinned ones.
 */
class LaneOrderer(private val map: LineMap, private val pinned: Map<Int, (IntArray) -> IntArray> = emptyMap()) {
    private val segs = map.segments
    private val idx = Array(segs.size) { s -> IntArray(map.routeCount) { -1 }.also { a -> segs[s].order.forEachIndexed { i, r -> a[r] = i } } }
    private val endRank = IntArray(segs.size * 2)

    /** Per node: transitions through it. */
    private val chordsAt = HashMap<Int, List<LineMap.Transition>>()

    init {
        chordsAt.putAll(map.transitions.groupBy { it.node })
        val endsAt = HashMap<Int, MutableList<Int>>()
        for ((s, seg) in segs.withIndex()) {
            endsAt.getOrPut(seg.first) { ArrayList() } += s * 2
            endsAt.getOrPut(seg.last) { ArrayList() } += s * 2 + 1
        }
        for ((node, ends) in endsAt) {
            val ang = ends.associateWith { e -> angleOut(e, node) }
            ends.sortedBy { ang.getValue(it) }.forEachIndexed { rank, e -> endRank[e] = rank }
        }
    }

    /**
     * Direction a segment leaves its node in, measured ~30 m out rather than on the first point: the
     * first metres of a segment are often a stub towards the junction centre, which would sort the
     * ends wrongly.
     */
    private fun angleOut(end: Int, node: Int): Double {
        val seg = segs[end / 2]
        val xs = seg.x; val ys = seg.y
        val ox = map.nodeX[node]; val oy = map.nodeY[node]
        val idxs = if (end % 2 == 0) xs.indices else xs.indices.reversed()
        var acc = 0.0; var px = xs[idxs.first]; var py = ys[idxs.first]
        var tx = px; var ty = py
        for (i in idxs) {
            acc += hypot(xs[i] - px, ys[i] - py); px = xs[i]; py = ys[i]
            tx = px; ty = py
            if (acc > 30.0) break
        }
        return atan2(ty - oy, tx - ox)
    }

    /** Position of [route]'s lane at [end] on the circle around its node. */
    private fun pos(end: Int, route: Int): Int {
        val s = end / 2
        val i = idx[s][route]
        val k = segs[s].order.size
        val lane = if (end % 2 == 1) i else k - 1 - i
        return endRank[end] * 128 + lane
    }

    fun nodeCost(node: Int): Int {
        val ch = chordsAt[node] ?: return 0
        val n = ch.size
        if (n < 2) return 0
        val lo = IntArray(n); val hi = IntArray(n)
        for (i in 0 until n) {
            val a = pos(ch[i].endA, ch[i].route); val b = pos(ch[i].endB, ch[i].route)
            lo[i] = min(a, b); hi[i] = max(a, b)
        }
        var cost = 0
        for (i in 0 until n) for (j in i + 1 until n) {
            if (ch[i].route == ch[j].route) continue
            val in1 = lo[j] > lo[i] && lo[j] < hi[i]
            val in2 = hi[j] > lo[i] && hi[j] < hi[i]
            if (in1 != in2) {
                val same = (ch[i].endA == ch[j].endA && ch[i].endB == ch[j].endB) || (ch[i].endA == ch[j].endB && ch[i].endB == ch[j].endA)
                cost += if (same) 2 else 1
            }
        }
        return cost
    }

    fun totalCost(): Int = chordsAt.keys.sumOf { nodeCost(it) }

    private fun setOrder(s: Int, order0: IntArray) {
        val order = pinned[s]?.invoke(order0) ?: order0
        segs[s].order = order
        order.forEachIndexed { i, r -> idx[s][r] = i }
    }

    private val endsAtNode = HashMap<Int, Int>().also { m -> for (seg in segs) { m.merge(seg.first, 1, Int::plus); m.merge(seg.last, 1, Int::plus) } }
    private val chordsByEnd: Map<Int, List<LineMap.Transition>> =
        map.transitions.flatMap { t -> listOf(t.endA to t, t.endB to t) }.groupBy({ it.first }, { it.second })

    private fun nodeOf(end: Int) = if (end % 2 == 0) segs[end / 2].first else segs[end / 2].last

    /**
     * Barycentre sweeps: at each end, the lanes that should come first counter-clockwise are those
     * whose other end lies farthest counter-clockwise (nested chords don't cross); each segment takes
     * the mean of what its two ends want. Keeps the best total found.
     */
    fun barycentre(sweeps: Int = 12) {
        var best = totalCost()
        var bestOrders = segs.map { it.order.copyOf() }
        repeat(sweeps) {
            for ((s, seg) in segs.withIndex()) {
                val k = seg.order.size
                if (k < 2) continue
                val score = DoubleArray(k) { 0.0 }; val weight = IntArray(k)
                for (end in listOf(s * 2, s * 2 + 1)) {
                    val ts = chordsByEnd[end] ?: continue
                    val node = nodeOf(end)
                    val total = (endsAtNode[node] ?: 1) * 128
                    val start = endRank[end] * 128 + 127
                    val want = HashMap<Int, Double>()
                    for (t in ts) {
                        val other = if (t.endA == end) t.endB else t.endA
                        val q = pos(other, t.route)
                        val dist = ((q - start) % total + total) % total
                        want.merge(t.route, -dist.toDouble()) { a, b -> (a + b) / 2 }
                    }
                    // Lanes with a wish, ranked counter-clockwise; the others keep their place.
                    val ranked = want.keys.sortedBy { want.getValue(it) }
                    ranked.forEachIndexed { lane, r ->
                        val i = idx[s][r]
                        val ccw = lane.toDouble() / max(1, ranked.size - 1) * (k - 1)
                        val stored = if (end % 2 == 1) ccw else (k - 1) - ccw
                        score[i] += stored; weight[i]++
                    }
                }
                val order = seg.order
                val key = DoubleArray(k) { i -> if (weight[i] == 0) i.toDouble() else score[i] / weight[i] }
                setOrder(s, order.indices.sortedWith(compareBy<Int> { key[it] }.thenBy { it }).map { order[it] }.toIntArray())
            }
            val c = totalCost()
            if (c < best) { best = c; bestOrders = segs.map { it.order.copyOf() } }
        }
        bestOrders.forEachIndexed { s, o -> setOrder(s, o) }
    }

    /** Local search, widest segments first, until a pass improves nothing (or [maxPasses]). */
    fun optimise(maxPasses: Int = 30) {
        val candidates = segs.indices.filter { segs[it].order.size >= 2 }.sortedByDescending { segs[it].order.size }
        for (pass in 0 until maxPasses) {
            var improved = false
            for (s in candidates) {
                val nodes = listOf(segs[s].first, segs[s].last).distinct().filter { it in chordsAt }
                if (nodes.isEmpty()) continue
                fun cost() = nodes.sumOf { nodeCost(it) }
                var best = cost()
                if (best == 0) continue
                var bestOrder = segs[s].order.copyOf()
                val k = bestOrder.size
                if (k <= 6) {
                    permutations(bestOrder.copyOf()) { perm ->
                        setOrder(s, perm.copyOf())
                        val c = cost()
                        if (c < best) { best = c; bestOrder = perm.copyOf() }
                    }
                } else {
                    var again = true
                    while (again) {
                        again = false
                        for (i in 0 until k) for (j in 0 until k) {
                            if (i == j) continue
                            val cand = bestOrder.toMutableList().also { val r = it.removeAt(i); it.add(j, r) }.toIntArray()
                            setOrder(s, cand)
                            val c = cost()
                            if (c < best) { best = c; bestOrder = cand; again = true }
                        }
                    }
                }
                val old = segs[s].order
                setOrder(s, bestOrder)
                if (!old.contentEquals(bestOrder)) improved = true
            }
            if (!improved) break
        }
    }

    /** Heap's algorithm. */
    private fun permutations(a: IntArray, visit: (IntArray) -> Unit) {
        val n = a.size
        val c = IntArray(n)
        visit(a)
        var i = 0
        while (i < n) {
            if (c[i] < i) {
                if (i % 2 == 0) { val t = a[0]; a[0] = a[i]; a[i] = t } else { val t = a[c[i]]; a[c[i]] = a[i]; a[i] = t }
                visit(a)
                c[i]++; i = 0
            } else { c[i] = 0; i++ }
        }
    }
}

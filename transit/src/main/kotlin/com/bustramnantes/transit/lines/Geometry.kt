package com.bustramnantes.transit.lines

import java.util.BitSet
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A growable int array (the builder handles ~60 k nodes and their sequences; boxing would dominate). */
internal class IntList(cap: Int = 8) {
    var a = IntArray(cap)
    var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(max(8, size * 2))
        a[size++] = v
    }
    operator fun get(i: Int) = a[i]
    fun last() = a[size - 1]
    fun toArray() = a.copyOf(size)
    fun contains(v: Int): Boolean {
        for (i in 0 until size) if (a[i] == v) return true
        return false
    }
}

internal fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

/** Undirected edge key. */
internal fun edgeKey(a: Int, b: Int): Long = if (a < b) pairKey(a, b) else pairKey(b, a)

/** Distance from point p to segment a-b. */
internal fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
    return hypot(ax + t * dx - px, ay + t * dy - py)
}

/** Douglas-Peucker: indices of the points to keep so the line stays within [tol] of the original. */
internal fun simplify(x: List<Double>, y: List<Double>, tol: Double): IntArray {
    if (x.size <= 2) return x.indices.toList().toIntArray()
    val keep = BooleanArray(x.size); keep[0] = true; keep[x.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>(); stack += 0 to x.size - 1
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var best = -1; var bd = tol
        for (i in a + 1 until b) {
            val dd = segDist(x[i], y[i], x[a], y[a], x[b], y[b])
            if (dd > bd) { bd = dd; best = i }
        }
        if (best >= 0) { keep[best] = true; stack += a to best; stack += best to b }
    }
    return keep.indices.filter { keep[it] }.toIntArray()
}

internal fun BitSet.toIntArray(): IntArray = stream().toArray()

/**
 * Edges, junctions and segments of the snapped node sequences, rebuilt at every clean-up round.
 *
 * A **junction** is a node where the set of lines changes or more than two edges meet; a
 * **segment** is a maximal chain between junctions, so all of its edges carry the same lines (LOOM's
 * "line graph"). A pure cycle with no junction becomes one closed segment.
 */
internal class Topology(seqs: List<IntArray>, seqRoute: List<Int>, val nx: DoubleArray, val ny: DoubleArray) {
    val edgeRoutes = HashMap<Long, BitSet>()
    val adj = HashMap<Int, IntList>()
    val segments = ArrayList<IntArray>()
    val segRoutes = ArrayList<BitSet>()

    /** (node, neighbour) -> segment end (segment * 2 + 0 first / 1 last). */
    val endAt = HashMap<Long, Int>()

    init {
        for ((s, seq) in seqs.withIndex()) for (i in 1 until seq.size) {
            val a = seq[i - 1]; val b = seq[i]
            val set = edgeRoutes.getOrPut(edgeKey(a, b)) {
                adj.getOrPut(a) { IntList(2) }.add(b); adj.getOrPut(b) { IntList(2) }.add(a)
                BitSet()
            }
            set.set(seqRoute[s])
        }
        val visited = HashSet<Long>()
        fun walk(start: Int, next: Int) {
            val chain = IntList(); chain.add(start)
            var prev = start; var cur = next
            visited += edgeKey(start, next)
            while (true) {
                chain.add(cur)
                if (isJunction(cur) || cur == start) break
                val n = adj.getValue(cur)
                val nxt = if (n[0] == prev) n[1] else n[0]
                if (!visited.add(edgeKey(cur, nxt))) break
                prev = cur; cur = nxt
            }
            val arr = chain.toArray()
            val id = segments.size
            segments += arr
            segRoutes += edgeRoutes.getValue(edgeKey(arr[0], arr[1]))
            endAt[pairKey(arr[0], arr[1])] = id * 2
            endAt[pairKey(arr[arr.size - 1], arr[arr.size - 2])] = id * 2 + 1
        }
        for ((v, n) in adj) if (isJunction(v)) for (i in 0 until n.size) if (edgeKey(v, n[i]) !in visited) walk(v, n[i])
        // Pure cycles (no junction at all).
        for ((v, n) in adj) for (i in 0 until n.size) if (edgeKey(v, n[i]) !in visited) walk(v, n[i])
    }

    fun isJunction(v: Int): Boolean {
        val n = adj[v] ?: return true
        if (n.size != 2) return true
        return edgeRoutes[edgeKey(v, n[0])] != edgeRoutes[edgeKey(v, n[1])]
    }

    fun length(s: IntArray): Double = (1 until s.size).sumOf { hypot(nx[s[it]] - nx[s[it - 1]], ny[s[it]] - ny[s[it - 1]]) }

    /** Every line's passage through a junction, from one segment end to another. */
    fun transitions(seqs: List<IntArray>, seqRoute: List<Int>): List<LineMap.Transition> {
        val seen = HashSet<String>()
        val out = ArrayList<LineMap.Transition>()
        for ((s, seq) in seqs.withIndex()) for (i in 1 until seq.size - 1) {
            val v = seq[i]
            if (!isJunction(v)) continue
            val ea = endAt[pairKey(v, seq[i - 1])] ?: continue
            val eb = endAt[pairKey(v, seq[i + 1])] ?: continue
            if (ea == eb) continue
            val r = seqRoute[s]
            val key = "$v:${min(ea, eb)}:${max(ea, eb)}:$r"
            if (seen.add(key)) out += LineMap.Transition(v, ea, eb, r)
        }
        return out
    }
}

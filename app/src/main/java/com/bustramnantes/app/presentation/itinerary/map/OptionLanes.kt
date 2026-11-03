package com.bustramnantes.app.presentation.itinerary.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Side-by-side layout of itinerary options that share a path, so none hides another on the results
 * map - after LOOM (Bast, Brosi & Storandt, "Efficient generation of geographically accurate transit
 * maps"):
 *
 *  1. **bundles** - where two options run along the same path (within one lane at the map's scale,
 *     same heading), cleaned of flicker;
 *  2. **lanes** - inside a bundle each option gets its own lane, offset from the shared centreline by
 *     `(position - (k-1)/2)` lanes, in dp (the map's line-width unit), so lanes hold at any zoom;
 *  3. **order** - LOOM optimises the order per edge with an ILP; with a handful of options one global
 *     order is searched exhaustively, scored like LOOM: a pair crosses each time one option joins or
 *     leaves the other from the side opposite its lane; ties go to the order keeping options that
 *     share the most distance next to each other. The order is computed once ([order]) and reused at
 *     every zoom ([layout]) so lanes don't swap while zooming;
 *  4. **smoothing** - offsets ramp in/out of bundles, taper to 0 at both ends (every option meets the
 *     start and arrival markers), and the small loops an offset line makes inside a sharp turn are cut out.
 *
 * Geometry is in Web Mercator metres ([toMercator]) - the map's own projection, so an offset of n dp
 * is n × [unitsPerDp] there. Pure Kotlin, unit-tested; a few ms for 6 options.
 */
class OptionLanes(options: List<List<Leg>>, private val unitsPerDp: Double, private val laneDp: Double = LANE_DP) {

    /** A leg in Web Mercator metres. */
    class Leg(val x: DoubleArray, val y: DoubleArray, val isWalk: Boolean)

    private val n = options.size
    /**
     * Sampling step: 1.5 dp, coarser on a long route zoomed in (at most [MAX_SAMPLES] per option -
     * a 20 km route at zoom 17 would otherwise be 30 k samples).
     */
    private val step = max(1.5 * unitsPerDp, options.maxOfOrNull(::length)?.div(MAX_SAMPLES) ?: 0.0)
    /** "Same path": within a lane (and within a step, samples of two options not lining up). */
    private val eps = max(1.1 * laneDp * unitsPerDp, 0.6 * step)
    private fun px(v: Double): Int = max(2, (v * unitsPerDp / step).roundToInt())

    private val legsOf = options
    private val sx: Array<DoubleArray>
    private val sy: Array<DoubleArray>
    private val legId: Array<IntArray>
    private val tx: Array<DoubleArray>
    private val ty: Array<DoubleArray>
    /** share[o][i * n + m]: option m runs alongside o (same heading) at o's sample i. */
    private val share: Array<BooleanArray>

    init {
        val dens = options.map { densify(it) }
        sx = Array(n) { dens[it].first }
        sy = Array(n) { dens[it].second }
        legId = Array(n) { dens[it].third }
        val t = (0 until n).map { tangents(sx[it], sy[it], px(8.0)) }
        tx = Array(n) { t[it].first }
        ty = Array(n) { t[it].second }
        share = computeShare()
    }

    private fun size(o: Int) = sx[o].size

    private fun length(legs: List<Leg>): Double =
        legs.sumOf { l -> (0 until l.x.size - 1).sumOf { hypot(l.x[it + 1] - l.x[it], l.y[it + 1] - l.y[it]) } }

    private fun densify(legs: List<Leg>): Triple<DoubleArray, DoubleArray, IntArray> {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ids = ArrayList<Int>()
        for ((li, leg) in legs.withIndex()) {
            for (k in 0 until leg.x.size - 1) {
                val dx = leg.x[k + 1] - leg.x[k]; val dy = leg.y[k + 1] - leg.y[k]
                val cnt = max(1, kotlin.math.ceil(hypot(dx, dy) / step).toInt())
                for (j in 0 until cnt) { xs += leg.x[k] + dx * j / cnt; ys += leg.y[k] + dy * j / cnt; ids += li }
            }
            if (leg.x.isNotEmpty()) { xs += leg.x.last(); ys += leg.y.last(); ids += li }
        }
        return Triple(xs.toDoubleArray(), ys.toDoubleArray(), ids.toIntArray())
    }

    private fun tangents(x: DoubleArray, y: DoubleArray, k: Int): Pair<DoubleArray, DoubleArray> {
        val m = x.size
        val ox = DoubleArray(m); val oy = DoubleArray(m)
        for (i in 0 until m) {
            val a = max(0, i - k); val b = min(m - 1, i + k)
            val dx = x[b] - x[a]; val dy = y[b] - y[a]
            val norm = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
            ox[i] = dx / norm; oy[i] = dy / norm
        }
        return ox to oy
    }

    private fun computeShare(): Array<BooleanArray> {
        val cell = eps
        val grid = HashMap<Long, ArrayList<Long>>()
        fun key(x: Double, y: Double) = (floor(x / cell).toLong() shl 32) or (floor(y / cell).toLong() and 0xffffffffL)
        for (o in 0 until n) for (i in 0 until size(o)) grid.getOrPut(key(sx[o][i], sy[o][i])) { ArrayList() } += (o.toLong() shl 32) or i.toLong()
        return Array(n) { o ->
            val s = BooleanArray(size(o) * n)
            for (i in 0 until size(o)) {
                s[i * n + o] = true
                val cx = floor(sx[o][i] / cell).toLong(); val cy = floor(sy[o][i] / cell).toLong()
                for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                    val list = grid[(gx shl 32) or (gy and 0xffffffffL)] ?: continue
                    for (packed in list) {
                        val m = (packed ushr 32).toInt(); val j = packed.toInt()
                        if (m == o || s[i * n + m]) continue
                        if (hypot(sx[m][j] - sx[o][i], sy[m][j] - sy[o][i]) > eps) continue
                        if (tx[o][i] * tx[m][j] + ty[o][i] * ty[m][j] > 0.7) s[i * n + m] = true
                    }
                }
            }
            for (m in 0 until n) if (m != o) clean(s, o, m, px(12.0))
            s
        }
    }

    /** Fills gaps shorter than [g] samples in o's sharing with m, then drops runs shorter than [g]. */
    private fun clean(s: BooleanArray, o: Int, m: Int, g: Int) {
        val len = size(o)
        fun get(i: Int) = s[i * n + m]
        fun set(i: Int, v: Boolean) { s[i * n + m] = v }
        var r = runs(len, ::get)
        for (k in 0 until r.size - 1) if (r[k + 1].first - r[k].second - 1 < g) for (i in r[k].second + 1 until r[k + 1].first) set(i, true)
        r = runs(len, ::get)
        for ((a, b) in r) if (b - a + 1 < g) for (i in a..b) set(i, false)
    }

    private fun runs(len: Int, on: (Int) -> Boolean): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < len) {
            if (on(i)) { var j = i; while (j + 1 < len && on(j + 1)) j++; out += i to j; i = j + 1 } else i++
        }
        return out
    }

    /** Lane order: rank per option (a higher rank sits further left, in the direction of travel). */
    fun order(): IntArray {
        if (n <= 1) return IntArray(n)
        // Join / leave events per pair, with the side the other option comes from / goes to.
        class Event(val a: Int, val b: Int, val bOnLeft: Boolean)
        val events = ArrayList<Event>()
        val look = px(10.0)
        val shared = Array(n) { DoubleArray(n) }
        for (a in 0 until n) for (b in a + 1 until n) {
            val len = size(a)
            val r = runs(len) { share[a][it * n + b] }
            shared[a][b] = r.sumOf { (s, e) -> (e - s + 1) * step }; shared[b][a] = shared[a][b]
            for ((s, e) in r) {
                for ((idx0, ref, ok) in listOf(Triple(s - look, s, s > 0), Triple(e + look, e, e < len - 1))) {
                    if (!ok) continue
                    val idx = idx0.coerceIn(0, len - 1)
                    var j = 0; var best = Double.MAX_VALUE
                    for (k in 0 until size(b)) { val d = hypot(sx[b][k] - sx[a][ref], sy[b][k] - sy[a][ref]); if (d < best) { best = d; j = k } }
                    val jj = (if (idx < s) j - look else j + look).coerceIn(0, size(b) - 1)
                    val vx = sx[b][jj] - sx[a][idx]; val vy = sy[b][jj] - sy[a][idx]
                    val cross = tx[a][ref] * vy - ty[a][ref] * vx
                    if (abs(cross) > 0.5 * eps) events += Event(a, b, cross > 0)
                }
            }
        }
        if (n > MAX_EXHAUSTIVE) return IntArray(n) { it }
        var bestRank = IntArray(n) { it }
        var bestCrossings = Int.MAX_VALUE; var bestSpread = Double.MAX_VALUE
        permutations(n) { perm ->
            val rank = IntArray(n).also { r -> perm.forEachIndexed { pos, o -> r[o] = pos } }
            val crossings = events.count { e -> e.bOnLeft != (rank[e.b] > rank[e.a]) }
            if (crossings > bestCrossings) return@permutations
            var spread = 0.0
            for (a in 0 until n) for (b in a + 1 until n) spread += shared[a][b] * (abs(rank[a] - rank[b]) - 1)
            if (crossings < bestCrossings || spread < bestSpread) {
                bestCrossings = crossings; bestSpread = spread; bestRank = rank
            }
        }
        return bestRank
    }

    /** Every option's legs, offset into its lane per [rank] (from [order]). */
    fun layout(rank: IntArray): List<List<Leg>> = (0 until n).map { o ->
        val len = size(o)
        val off = DoubleArray(len) { i ->
            var k = 0; var pos = 0
            for (m in 0 until n) if (share[o][i * n + m]) { k++; if (rank[m] < rank[o]) pos++ }
            (pos - (k - 1) / 2.0) * laneDp * unitsPerDp
        }
        val smooth = smooth(off, px(16.0))
        val ramp = px(20.0).toDouble()
        val x = DoubleArray(len); val y = DoubleArray(len)
        for (i in 0 until len) {
            val taper = min(1.0, min(i, len - 1 - i) / ramp)
            val d = smooth[i] * taper
            x[i] = sx[o][i] - ty[o][i] * d   // left normal = (-ty, tx)
            y[i] = sy[o][i] + tx[o][i] * d
        }
        val keep = cutLoops(x, y, px(40.0))
        legsOf[o].indices.map { li ->
            val idx = (0 until len).filter { legId[o][it] == li && keep[it] }
            Leg(DoubleArray(idx.size) { x[idx[it]] }, DoubleArray(idx.size) { y[idx[it]] }, legsOf[o][li].isWalk)
        }
    }

    private fun smooth(v: DoubleArray, w: Int): DoubleArray {
        if (w < 2 || v.size < 3) return v
        val half = w / 2
        return DoubleArray(v.size) { i ->
            var sum = 0.0
            for (k in i - half until i - half + w) sum += v[k.coerceIn(0, v.size - 1)]
            sum / w
        }
    }

    /** Removes small self-loops (inside of sharp turns); moves the joining point onto the crossing. */
    private fun cutLoops(x: DoubleArray, y: DoubleArray, window: Int): BooleanArray {
        val keep = BooleanArray(x.size) { true }
        var i = 0
        while (i < x.size - 2) {
            var hit = -1
            for (j in min(x.size - 2, i + window) downTo i + 2) {
                val t = segmentCross(x[i], y[i], x[i + 1], y[i + 1], x[j], y[j], x[j + 1], y[j + 1])
                if (t != null) { x[j] = x[i] + (x[i + 1] - x[i]) * t; y[j] = y[i] + (y[i + 1] - y[i]) * t; hit = j; break }
            }
            if (hit > 0) { for (k in i + 1 until hit) keep[k] = false; i = hit } else i++
        }
        return keep
    }

    companion object {
        /** Lane spacing (dp): line width + a hairline. */
        const val LANE_DP = 6.0
        private const val MAX_EXHAUSTIVE = 7
        private const val MAX_SAMPLES = 4_000.0
        private const val EARTH_RADIUS = 6_378_137.0

        /** Web Mercator metres per dp at [zoom] (MapLibre: 512-dp tiles). */
        fun unitsPerDp(zoom: Double): Double = 2 * PI * EARTH_RADIUS / (512.0 * 2.0.pow(zoom))

        fun toMercator(lat: Double, lon: Double): Pair<Double, Double> =
            Math.toRadians(lon) * EARTH_RADIUS to ln(tan(PI / 4 + Math.toRadians(lat) / 2)) * EARTH_RADIUS

        fun fromMercator(x: Double, y: Double): MapPoint =
            MapPoint(Math.toDegrees(2 * atan(exp(y / EARTH_RADIUS)) - PI / 2), Math.toDegrees(x / EARTH_RADIUS))

        /** Intersection parameter on segment 1 when segments 1 and 2 cross strictly, else null. */
        internal fun segmentCross(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double, x4: Double, y4: Double): Double? {
            val d1x = x2 - x1; val d1y = y2 - y1; val d2x = x4 - x3; val d2y = y4 - y3
            val den = d1x * d2y - d1y * d2x
            if (den == 0.0) return null
            val wx = x3 - x1; val wy = y3 - y1
            val t = (wx * d2y - wy * d2x) / den
            val u = (wx * d1y - wy * d1x) / den
            return if (t > 0 && t < 1 && u > 0 && u < 1) t else null
        }

        /** Heap's algorithm; [visit] gets the same array mutated between calls. */
        private fun permutations(n: Int, visit: (IntArray) -> Unit) {
            val a = IntArray(n) { it }
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
}

package com.bustramnantes.transit.lines

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What to stroke to draw a [LineMap] at draw zoom [zoom]: every line in its lane, as polylines in Web
 * Mercator metres, plus the station dashes. The app turns them into MapLibre features; the comparison
 * renderer (`LineMapComparisonTest`) strokes the same pieces with Java2D, so what was compared with the
 * official tiles is what the app draws.
 *
 * - **Lanes**: a segment's lines side by side in its [LineMap.Segment.order], each offset from the
 *   centreline by its place in the bundle (widths and gap from [LineStyle] at [zoom]). Offsets are
 *   computed here, not with MapLibre's `line-offset`: that one is constant per feature and per zoom
 *   step, and can't join a lane to the next segment's lane at another offset.
 * - **Junctions**: a lane that continues into another segment stops short of the junction (by half
 *   the widest bundle there, plus 2 dp) and a curve joins it to its next lane: lines swap sides
 *   smoothly instead of crossing at a point. A lane ending at its terminus runs to the node.
 * - **Offsets** use miter joins (capped so a sharp turn doesn't spike) and cut the small self-loops
 *   an offset makes on the inside of a tight turn.
 *
 * The result is only valid at [zoom]: the app recomputes it when the zoom changes enough (see
 * `LineMapLayers` in the app) and scales it in between, like a raster tile.
 */
class LaneGeometry private constructor(val pieces: List<Piece>, val dashes: List<Dash>) {

    /**
     * One stroke of [route]: a lane along segment [segment] ([connector] false), or the curve joining
     * two lanes at a junction ([connector] true; [segment] is then the segment it leaves).
     */
    class Piece(val route: Int, val segment: Int, val connector: Boolean, val x: DoubleArray, val y: DoubleArray)

    /** A station's dash, from ([x0], [y0]) to ([x1], [y1]), [thicknessDp] thick. */
    class Dash(val stopId: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val thicknessDp: Double)

    companion object {
        /** Stop marker radius by zoom (dp), as the Plan tab's stop dots. */
        val MARKER_RADIUS = listOf(11.0 to 4.0, 14.0 to 7.0, 18.0 to 12.0)

        /** Stop marker ring width by zoom (dp). */
        val MARKER_STROKE = listOf(11.0 to 1.5, 18.0 to 3.0)

        /** Samples per junction curve: smooth at any zoom the layouts are drawn at. */
        private const val CURVE_POINTS = 10

        /**
         * Lanes and dashes of [map] at draw zoom [zoom]. [bbox] (minX, minY, maxX, maxY, Mercator
         * metres) skips segments wholly outside it.
         */
        fun compute(map: LineMap, zoom: Double, bbox: DoubleArray? = null): LaneGeometry {
            val style = map.style
            val upd = WebMercator.unitsPerDp(zoom)
            val gap = style.gap(zoom)
            fun width(route: Int) = style.width(route, zoom)
            fun offset(order: IntArray, i: Int): Double {
                var acc = 0.0
                for (j in 0 until i) acc += width(order[j]) + gap
                return style.bundleWidth(order, zoom) / 2 - acc - width(order[i]) / 2
            }
            val segs = map.segments
            val radius = HashMap<Int, Double>()
            for (s in segs) for (n in listOf(s.first, s.last)) {
                radius[n] = max(radius[n] ?: 0.0, (style.bundleWidth(s.order, zoom) / 2 + 2) * upd)
            }
            val continues = HashSet<Long>()
            for (t in map.transitions) { continues += pairKey(t.endA, t.route); continues += pairKey(t.endB, t.route) }
            val pieces = ArrayList<Piece>()
            val lanes = HashMap<Long, Piece>()
            for ((si, s) in segs.withIndex()) {
                if (bbox != null && (s.x.all { it < bbox[0] } || s.x.all { it > bbox[2] } || s.y.all { it < bbox[1] } || s.y.all { it > bbox[3] })) continue
                val len = arcLength(s.x, s.y)
                for ((i, r) in s.order.withIndex()) {
                    if (style.kind[r] == LineStyle.Kind.HIDDEN) continue
                    var a = if (pairKey(si * 2, r) in continues) radius.getValue(s.first) else 0.0
                    var b = if (pairKey(si * 2 + 1, r) in continues) radius.getValue(s.last) else 0.0
                    if (a + b > 0.9 * len) { val f = 0.9 * len / (a + b); a *= f; b *= f }
                    val (tx, ty) = sub(s.x, s.y, a, len - b)
                    val (ox, oy) = offsetLine(tx, ty, offset(s.order, i) * upd)
                    val piece = Piece(r, si, false, ox, oy)
                    lanes[pairKey(si, r)] = piece
                    pieces += piece
                }
            }
            for (t in map.transitions) {
                val la = lanes[pairKey(t.endA / 2, t.route)] ?: continue
                val lb = lanes[pairKey(t.endB / 2, t.route)] ?: continue
                val (pa, da) = endOf(la, t.endA % 2 == 1, arriving = true)
                val (pb, db) = endOf(lb, t.endB % 2 == 1, arriving = false)
                val l = 0.45 * hypot(pb[0] - pa[0], pb[1] - pa[1])
                val c1x = pa[0] + da[0] * l; val c1y = pa[1] + da[1] * l
                val c2x = pb[0] - db[0] * l; val c2y = pb[1] - db[1] * l
                val xs = DoubleArray(CURVE_POINTS + 1); val ys = DoubleArray(CURVE_POINTS + 1)
                for (j in 0..CURVE_POINTS) {
                    val u = j.toDouble() / CURVE_POINTS; val v = 1 - u
                    xs[j] = v * v * v * pa[0] + 3 * v * v * u * c1x + 3 * v * u * u * c2x + u * u * u * pb[0]
                    ys[j] = v * v * v * pa[1] + 3 * v * v * u * c1y + 3 * v * u * u * c2y + u * u * u * pb[1]
                }
                pieces += Piece(t.route, t.endA / 2, true, xs, ys)
            }
            val dashes = ArrayList<Dash>()
            if (zoom >= LineMap.HUB_DASH_MIN_ZOOM) {
                val r = LineStyle.interp(zoom, MARKER_RADIUS)
                for (m in map.stops) {
                    if (!m.hub || m.dashSegment < 0) continue
                    if (bbox != null && (m.x < bbox[0] || m.x > bbox[2] || m.y < bbox[1] || m.y > bbox[3])) continue
                    val bw = style.bundleWidth(segs[m.dashSegment].order, zoom)
                    // Across the widest bundle, as long as it is wide (with a marker radius each side).
                    val half = max(3 * r, bw + 2 * r) / 2 * upd
                    val ux = -sin(m.dashAngle); val uy = cos(m.dashAngle)
                    dashes += Dash(m.stopId, m.x - ux * half, m.y - uy * half, m.x + ux * half, m.y + uy * half, 1.4 * r)
                }
            }
            return LaneGeometry(pieces, dashes)
        }

        private fun arcLength(x: DoubleArray, y: DoubleArray) = (1 until x.size).sumOf { hypot(x[it] - x[it - 1], y[it] - y[it - 1]) }

        /** End point and travel direction of a lane at its first ([last] false) or last point. */
        private fun endOf(l: Piece, last: Boolean, arriving: Boolean): Pair<DoubleArray, DoubleArray> {
            val n = l.x.size
            val i = if (last) n - 1 else 0
            val j = if (last) max(0, n - 2) else min(n - 1, 1)
            var dx = l.x[i] - l.x[j]; var dy = l.y[i] - l.y[j] // pointing out of the lane at that end
            val dn = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
            dx /= dn; dy /= dn
            val p = doubleArrayOf(l.x[i], l.y[i])
            return if (arriving) p to doubleArrayOf(dx, dy) else p to doubleArrayOf(-dx, -dy)
        }

        /** The part of the polyline between arc lengths [a] and [b]. */
        private fun sub(x: DoubleArray, y: DoubleArray, a: Double, b: Double): Pair<DoubleArray, DoubleArray> {
            val ox = ArrayList<Double>(); val oy = ArrayList<Double>()
            var acc = 0.0
            fun at(i: Int, t: Double) { ox += x[i] + (x[i + 1] - x[i]) * t; oy += y[i] + (y[i + 1] - y[i]) * t }
            for (i in 0 until x.size - 1) {
                val l = hypot(x[i + 1] - x[i], y[i + 1] - y[i])
                val s0 = acc; val s1 = acc + l
                if (l > 0) {
                    if (ox.isEmpty() && s1 >= a) at(i, ((a - s0) / l).coerceIn(0.0, 1.0))
                    if (ox.isNotEmpty()) {
                        if (s1 >= b) { at(i, ((b - s0) / l).coerceIn(0.0, 1.0)); break }
                        ox += x[i + 1]; oy += y[i + 1]
                    }
                }
                acc = s1
            }
            if (ox.size < 2) { ox.clear(); oy.clear(); ox += x.first(); oy += y.first(); ox += x.last(); oy += y.last() }
            return ox.toDoubleArray() to oy.toDoubleArray()
        }

        /** Offsets a polyline [d] to the left (miter joins, capped at 2x), small loops on the inside of turns cut. */
        private fun offsetLine(x: DoubleArray, y: DoubleArray, d: Double): Pair<DoubleArray, DoubleArray> {
            val n = x.size
            if (d == 0.0) return x to y
            val ox = DoubleArray(n); val oy = DoubleArray(n)
            fun nrm(i: Int): DoubleArray {
                val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]; val l = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
                return doubleArrayOf(-dy / l, dx / l)
            }
            for (i in 0 until n) {
                val n1 = if (i > 0) nrm(i - 1) else nrm(0)
                val n2 = if (i < n - 1) nrm(i) else nrm(n - 2)
                var mx = n1[0] + n2[0]; var my = n1[1] + n2[1]
                val ml = hypot(mx, my).takeIf { it > 1e-9 } ?: 1.0
                mx /= ml; my /= ml
                val c = (mx * n1[0] + my * n1[1]).coerceAtLeast(0.5)
                ox[i] = x[i] + mx * d / c; oy[i] = y[i] + my * d / c
            }
            val keep = BooleanArray(n) { true }
            var i = 0
            while (i < n - 2) {
                var hit = -1
                for (j in min(n - 2, i + 40) downTo i + 2) {
                    val t = cross(ox[i], oy[i], ox[i + 1], oy[i + 1], ox[j], oy[j], ox[j + 1], oy[j + 1])
                    if (t != null) { ox[j] = ox[i] + (ox[i + 1] - ox[i]) * t; oy[j] = oy[i] + (oy[i + 1] - oy[i]) * t; hit = j; break }
                }
                if (hit > 0) { for (q in i + 1 until hit) keep[q] = false; i = hit } else i++
            }
            val idx = (0 until n).filter { keep[it] }
            return DoubleArray(idx.size) { ox[idx[it]] } to DoubleArray(idx.size) { oy[idx[it]] }
        }

        private fun cross(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double, x4: Double, y4: Double): Double? {
            val d1x = x2 - x1; val d1y = y2 - y1; val d2x = x4 - x3; val d2y = y4 - y3
            val den = d1x * d2y - d1y * d2x
            if (den == 0.0) return null
            val wx = x3 - x1; val wy = y3 - y1
            val t = (wx * d2y - wy * d2x) / den
            val u = (wx * d1y - wy * d1x) / den
            return if (t > 0 && t < 1 && u > 0 && u < 1) t else null
        }
    }
}

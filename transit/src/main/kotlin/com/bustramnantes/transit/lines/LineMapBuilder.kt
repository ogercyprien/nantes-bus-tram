package com.bustramnantes.transit.lines

import com.bustramnantes.transit.Pattern
import com.bustramnantes.transit.RideShapes
import com.bustramnantes.transit.TransitNetwork
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the [LineMap] of [net] for layout zoom [zoom], after LOOM (Bast, Brosi & Storandt,
 * "Efficient generation of geographically accurate transit maps"). `README.md` next to this file
 * tells the whole story, with the case behind every rule; the KDoc here says what each pass does and
 * why it is there.
 *
 * 1. **Which hops are drawn** ([HopRules]): minor variants, detours and failed street paths are
 *    left out, as on the official map.
 * 2. **Snapping** ([snap]): each drawn run of a pattern, along its OSM ride path, is sampled every few
 *    metres and each sample joins the nearest node already laid down within the merge distance (same
 *    axis, either direction), else becomes a new node. Both directions of a street, double tram tracks
 *    and dual carriageways fold into one corridor. Trams first, then Busway/Chronobus, then buses:
 *    the main lines lay down the corridors the others snap onto.
 * 3. **Stations** ([placeHubs]): a stop with many lines and quays spread out becomes one junction.
 * 4. **Clean-up rounds** ([cleanUp]): short segments contracted, parallel twins and bubbles merged,
 *    overlapping corridors merged, knots cut, roundabouts contracted; until nothing changes.
 * 5. **Segments, stop markers, lane order** ([finish]).
 *
 * Two corridors merge where their bundles, as drawn at [zoom] ([LineStyle]), would overlap: (width
 * here + width there) / 2 + a gap apart, never less than [MIN_MERGE_DP] dp (both directions of a
 * street, double tram tracks) nor more than [MAX_MERGE_DP]. That is why there is one layout per
 * zoom: at z12 the whole centre is one bundle, at z15 parallel streets 100 m apart stay apart.
 */
class LineMapBuilder(
    private val net: TransitNetwork,
    private val zoom: Int,
    private val routes: List<MapRoute> = MapRoute.of(net),
) {
    val style = LineStyle(routes)
    private val z = zoom.toDouble()

    /** Ground metres -> Web Mercator metres. */
    private val k = WebMercator.NANTES_SCALE
    private val upd = WebMercator.unitsPerDp(z)
    private val gapDp = style.gap(z)

    /** Merge floor, also the unit of the clean-up steps: 12 m on the ground at least. */
    private val d = max(12.0 * k, MIN_MERGE_DP * upd)
    private val dMax = max(d, MAX_MERGE_DP * upd)

    /** Sampling step along ride paths: a few metres, about a dp. */
    private val step = max(4.0 * k, 1.2 * upd)

    // Nodes: position, weight (samples averaged in), axis (doubled-angle vector, so both directions of
    // a street agree), the lines through it and their bundle width, and its rank.
    private val nodeRoutes = ArrayList<BitSet>()
    private var nodeWidth = DoubleArray(1 shl 16)
    private var nx = DoubleArray(1 shl 16)
    private var ny = DoubleArray(1 shl 16)
    private var nw = DoubleArray(1 shl 16)

    /**
     * Rank of a node's most important line ([LineStyle.rank]: tram 2 > Busway/Chronobus 1 > bus 0;
     * a station [HUB_RANK]). Only lines of the node's rank move it: a bus snapping onto the tram
     * corridor, or merged with it, bends to the trams instead of dragging them sideways (Aimé Delrue
     * at z13: trams 2/3 merged with 5/26/NA coming up from République made a sharp corner in an
     * otherwise straight tram line).
     */
    private var nodeRank = IntArray(1 shl 16)
    private var ax = DoubleArray(1 shl 16)
    private var ay = DoubleArray(1 shl 16)
    private var nn = 0
    private val grid = HashMap<Long, IntList>()
    private val adj = ArrayList<IntList>()

    // Union-find over the nodes, with each cluster's bounding box (see [union]).
    private lateinit var uf: IntArray
    private lateinit var bx0: DoubleArray
    private lateinit var bx1: DoubleArray
    private lateinit var by0: DoubleArray
    private lateinit var by1: DoubleArray

    // Snapping results.
    private val seqs = ArrayList<IntArray>()
    private val seqRoute = ArrayList<Int>()
    private val quayNode = HashMap<Int, IntList>()

    /** Per quay, the sequences stopping there (indices into [seqs]). */
    private val quaySeqs = HashMap<Int, IntList>()

    /** Station StopPlace -> a node of its hub (its marker sits on the hub's junction). */
    private val hubStops = HashMap<Int, Int>()
    private val hubSeeds = IntList()

    /** Chord (edge key, current roots) -> the path its lines take instead (see [cleanUp]). */
    private val rewrites = HashMap<Long, IntArray>()

    fun build(log: (String) -> Unit = {}): LineMap {
        val t0 = System.nanoTime()
        val rules = HopRules(log)
        snap(rules, log)
        log("snapped: $nn nodes, ${seqs.sumOf { it.size }} sequence nodes in ${(System.nanoTime() - t0) / 1_000_000} ms")
        uf = IntArray(nn) { it }
        bx0 = nx.copyOf(nn); bx1 = nx.copyOf(nn); by0 = ny.copyOf(nn); by1 = ny.copyOf(nn)
        placeHubs(rules.shown)
        log("${hubStops.size} station hubs")
        val current = cleanUp(log)
        val map = finish(current, log)
        log("built in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return map
    }

    // ------------------------------------------------------------------------------------------
    // 1. Which hops are drawn
    // ------------------------------------------------------------------------------------------

    /**
     * Decides, per line and stop-to-stop hop, whether it is drawn. The official map shows each line's
     * usual route, not every variant in the timetable, so:
     *
     * - **Minor variants**: a hop carrying under [MIN_HOP_SHARE] of the trips of its line's busiest
     *   hop isn't drawn. That hides tram 2's runs to Espace Diderot (Fridays and weekends) and to
     *   Neustrie via Les Couëts (depot runs): real in the GTFS, on neither map.
     * - **Detours**: where two patterns of a line split and rejoin (same stop before and after, other
     *   stops in between), the way carrying under [DETOUR_SHARE] of the other's trips isn't drawn:
     *   TE1 via Tabarly (Rue de l'Île Macé, 28 trips) beside TE1 via Sarrail (Bd Général de Gaulle, 68);
     *   the official map has only 36 on Rue de l'Île Macé. A branch that never rejoins is kept.
     * - **Exception to both**: a run of such hops is drawn anyway when it is the only line drawn at
     *   one of its stops. Line 30's runs to Îles de Loire (27 trips vs 250) serve 9 stops nothing else
     *   does and went missing without it. Tram 2's depot runs serve no stop of their own: still hidden.
     *   Known cost: 88's branch via Gare de Bouaye is hidden because 98 serves its stops.
     * - **Straight hops**: a hop with no street path (OSM routing failed) is a straight line, drawn
     *   only up to [MAX_STRAIGHT_HOP_M]: longer, it cuts across blocks and looks like a real street.
     *   Boats are exempt: a straight line on the water is fine (and they follow `route=ferry` ways
     *   since the ride paths learned them).
     */
    private inner class HopRules(private val log: (String) -> Unit) {
        val shown: List<Int> = net.patterns.indices.filter { style.kind[net.patterns[it].route] != LineStyle.Kind.HIDDEN }
        private val patternTrips = IntArray(net.patterns.size).also { c -> for (t in 0 until net.tripCount) c[net.tripPattern[t]]++ }

        /** (route, hop) -> trips. */
        private val hopTrips = HashMap<Long, Int>()
        private val busiest = IntArray(net.routes.size)
        private val detourHops = HashSet<Long>()
        private val ownStopHops = HashSet<Long>()
        private val shapes = net.rideShapes
        val straightDropped = HashSet<String>()

        fun hopKey(route: Int, a: Int, b: Int): Long = (route.toLong() shl 48) xor RideShapes.key(net.quayNumber[a], net.quayNumber[b])

        init {
            for ((p, pat) in net.patterns.withIndex()) for (i in 0 until pat.quays.size - 1) {
                val key = hopKey(pat.route, pat.quays[i], pat.quays[i + 1])
                val n = (hopTrips[key] ?: 0) + patternTrips[p]
                hopTrips[key] = n
                busiest[pat.route] = max(busiest[pat.route], n)
            }
            findDetours()
            findOwnStopRuns()
        }

        private fun findDetours() {
            val detourLog = HashSet<String>()
            for ((route, pats) in shown.groupBy { net.patterns[it].route }) {
                val stopsOf = pats.associateWith { p -> IntArray(net.patterns[p].quays.size) { net.quayStopPlace[net.patterns[p].quays[it]] } }

                /** Trips of the route's patterns running [seq] contiguously. */
                fun tripsAlong(seq: IntArray): Int = pats.sumOf { r ->
                    val s = stopsOf.getValue(r)
                    val hit = (0..s.size - seq.size).any { o -> seq.indices.all { s[o + it] == seq[it] } }
                    if (hit) patternTrips[r] else 0
                }
                for (p in pats) for (q in pats) {
                    if (p == q) continue
                    val sp = stopsOf.getValue(p); val sq = stopsOf.getValue(q)
                    // Monotone anchors: stops of p also in q, in order.
                    var lastI = -1; var lastJ = -1
                    for (i in sp.indices) {
                        var j = -1
                        for (t in lastJ + 1 until sq.size) if (sq[t] == sp[i]) { j = t; break }
                        if (j < 0) continue
                        if (lastI >= 0 && (i - lastI > 1 || j - lastJ > 1) &&
                            !(i - lastI == j - lastJ && (1 until i - lastI).all { sp[lastI + it] == sq[lastJ + it] })
                        ) {
                            val wayP = sp.copyOfRange(lastI, i + 1); val wayQ = sq.copyOfRange(lastJ, j + 1)
                            val tp = tripsAlong(wayP); val tq = tripsAlong(wayQ)
                            if (tp < DETOUR_SHARE * tq) {
                                val quays = net.patterns[p].quays
                                // Only hops the detour alone runs (not one the main way shares).
                                for (h in lastI until i) {
                                    val key = hopKey(route, quays[h], quays[h + 1])
                                    if (hopTrips.getValue(key) <= tp) detourHops += key
                                }
                                detourLog += "${net.routes[route].shortName} " + wayP.joinToString(">") { net.stopPlaces[it].name } + " ($tp vs $tq)"
                            }
                        }
                        lastI = i; lastJ = j
                    }
                }
            }
            if (detourLog.isNotEmpty()) log("detours not drawn: ${detourLog.sorted()}")
        }

        private fun frequent(route: Int, key: Long) = hopTrips.getValue(key) >= MIN_HOP_SHARE * busiest[route] && key !in detourHops

        private fun findOwnStopRuns() {
            val servedStops = HashSet<Int>()
            for (p in shown) {
                val pat = net.patterns[p]
                for (i in 0 until pat.quays.size - 1) if (frequent(pat.route, hopKey(pat.route, pat.quays[i], pat.quays[i + 1]))) {
                    servedStops += net.quayStopPlace[pat.quays[i]]; servedStops += net.quayStopPlace[pat.quays[i + 1]]
                }
            }
            for (p in shown) {
                val pat = net.patterns[p]
                var a = 0
                while (a < pat.quays.size - 1) {
                    if (frequent(pat.route, hopKey(pat.route, pat.quays[a], pat.quays[a + 1]))) { a++; continue }
                    var b = a
                    while (b < pat.quays.size - 1 && !frequent(pat.route, hopKey(pat.route, pat.quays[b], pat.quays[b + 1]))) b++
                    if ((a..b).any { net.quayStopPlace[pat.quays[it]] !in servedStops }) {
                        for (h in a until b) ownStopHops += hopKey(pat.route, pat.quays[h], pat.quays[h + 1])
                    }
                    a = b
                }
            }
        }

        private fun straightTooLong(pat: Pattern, pos: Int): Boolean {
            if (style.kind[pat.route] == LineStyle.Kind.BOAT) return false
            val a = pat.quays[pos]; val b = pat.quays[pos + 1]
            if (shapes != null && shapes.indexOf(net.quayNumber[a], net.quayNumber[b]) >= 0) return false
            val len = hypot(
                WebMercator.x(net.quayLon[b]) - WebMercator.x(net.quayLon[a]),
                WebMercator.y(net.quayLat[b]) - WebMercator.y(net.quayLat[a]),
            ) / k
            if (len <= MAX_STRAIGHT_HOP_M) return false
            straightDropped += "${net.routes[pat.route].shortName} ${net.stopPlaces[net.quayStopPlace[a]].name} -> " +
                "${net.stopPlaces[net.quayStopPlace[b]].name} (${len.toInt()} m)"
            return true
        }

        /** Whether hop [pos] of [pat] (quay pos -> pos + 1) is drawn. */
        fun drawn(pat: Pattern, pos: Int): Boolean {
            val key = hopKey(pat.route, pat.quays[pos], pat.quays[pos + 1])
            return (frequent(pat.route, key) || key in ownStopHops) && !straightTooLong(pat, pos)
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. Snapping
    // ------------------------------------------------------------------------------------------

    private fun cell(v: Double) = floor(v / dMax).toLong()

    private fun newNode(x: Double, y: Double, c2: Double, s2: Double, rank: Int): Int {
        if (nn == nx.size) {
            val c = nn * 2
            nx = nx.copyOf(c); ny = ny.copyOf(c); nw = nw.copyOf(c); ax = ax.copyOf(c); ay = ay.copyOf(c)
            nodeWidth = nodeWidth.copyOf(c); nodeRank = nodeRank.copyOf(c)
        }
        nodeRoutes += BitSet(); nodeWidth[nn] = 0.0
        nx[nn] = x; ny[nn] = y; nw[nn] = 1.0; ax[nn] = c2; ay[nn] = s2; nodeRank[nn] = rank
        grid.getOrPut(pairKey(cell(x).toInt(), cell(y).toInt())) { IntList() }.add(nn)
        adj += IntList(4)
        return nn++
    }

    /** A sample joins node [j]: the node moves only for samples of its own rank (see [nodeRank]). */
    private fun absorb(j: Int, x: Double, y: Double, c2: Double, s2: Double, rank: Int) {
        ax[j] += c2; ay[j] += s2
        when {
            rank < nodeRank[j] -> return
            rank > nodeRank[j] -> { nx[j] = x; ny[j] = y; nw[j] = 1.0; nodeRank[j] = rank }
            else -> { val w = nw[j]; nx[j] = (nx[j] * w + x) / (w + 1); ny[j] = (ny[j] * w + y) / (w + 1); nw[j] = w + 1 }
        }
    }

    private fun link(a: Int, b: Int) {
        if (a == b || adj[a].contains(b)) return
        adj[a].add(b); adj[b].add(a)
    }

    /**
     * A short path a -> b in the graph built so far (at most 1.5 x crow-fly + 2 steps, 60 nodes
     * settled), interior nodes only. When a sample jumps to a node that isn't a neighbour of the
     * previous one, following the existing corridor instead of a new edge keeps the graph from
     * growing chords across its own corridors.
     */
    private fun shortPath(a: Int, b: Int): IntArray? {
        val limit = 1.5 * hypot(nx[b] - nx[a], ny[b] - ny[a]) + 2 * step
        val dist = HashMap<Int, Double>(); val prev = HashMap<Int, Int>()
        val queue = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[a] = 0.0; queue += 0.0 to a
        var settled = 0
        while (queue.isNotEmpty() && settled < 60) {
            val (du, u) = queue.poll()
            if (du > (dist[u] ?: Double.MAX_VALUE)) continue
            settled++
            if (u == b) break
            val list = adj[u]
            for (i in 0 until list.size) {
                val v = list[i]
                val nd = du + hypot(nx[v] - nx[u], ny[v] - ny[u])
                if (nd <= limit && nd < (dist[v] ?: Double.MAX_VALUE)) { dist[v] = nd; prev[v] = u; queue += nd to v }
            }
        }
        if (b !in prev) return null
        val out = ArrayList<Int>()
        var c = prev.getValue(b)
        while (c != a) { out += c; c = prev.getValue(c) }
        return out.reversed().toIntArray()
    }

    private fun addRoute(v: Int, route: Int) {
        val set = nodeRoutes[v]
        if (set[route]) return
        set.set(route)
        nodeWidth[v] += style.width(route, z) + if (set.cardinality() > 1) gapDp else 0.0
    }

    /**
     * Snaps one run of a pattern ([xs]/[ys], its quays at sample indices [quayAt]); returns its node
     * sequence and the node of each quay.
     *
     * A sample joins the nearest node within the merge distance, if that node's axis is within 30° of
     * the sample's (a crossing street doesn't capture it). The distance depends on the node: within
     * [d] for the previous node or a node of the same line (staying close: else nodes end up a merge
     * radius apart and the line gets coarse, while every few metres would be noise and triangles), up
     * to the two bundles' half widths plus a gap for other lines. A node met recently in this run
     * can't be joined again except as the previous one: a line looping back past itself (a terminus
     * loop) would otherwise short-circuit the loop.
     */
    private fun insert(xs: DoubleArray, ys: DoubleArray, quayAt: IntArray, route: Int): Pair<IntArray, IntArray> {
        val w = style.width(route, z)
        val rank = style.rank(route)
        // Densify, remembering the sample of each quay.
        val px = ArrayList<Double>(); val py = ArrayList<Double>()
        val quaySample = IntArray(quayAt.size)
        var q = 0
        for (i in xs.indices) {
            while (q < quayAt.size && quayAt[q] == i) { quaySample[q] = px.size; q++ }
            px += xs[i]; py += ys[i]
            if (i == xs.size - 1) break
            val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]
            val n = max(1, ceil(hypot(dx, dy) / step).toInt())
            for (j in 1 until n) { px += xs[i] + dx * j / n; py += ys[i] + dy * j / n }
        }
        val m = px.size
        val seq = IntList(m)
        val sampleNode = IntArray(m)
        val recent = ArrayDeque<Int>(); val recentSet = HashMap<Int, Int>()
        val window = max(20, (6 * dMax / step).toInt())
        for (i in 0 until m) {
            val a = max(0, i - 2); val b = min(m - 1, i + 2)
            var tx = px[b] - px[a]; var ty = py[b] - py[a]
            val tn = hypot(tx, ty).takeIf { it > 0 } ?: 1.0
            tx /= tn; ty /= tn
            val c2 = tx * tx - ty * ty; val s2 = 2 * tx * ty
            val last = if (seq.size > 0) seq.last() else -1
            var best = -1; var bd = Double.MAX_VALUE
            val cx = cell(px[i]); val cy = cell(py[i])
            for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                val list = grid[pairKey(gx.toInt(), gy.toInt())] ?: continue
                for (t in 0 until list.size) {
                    val j = list[t]
                    val dist = hypot(nx[j] - px[i], ny[j] - py[i])
                    if (dist >= bd) continue
                    val thr = when {
                        j == last -> d
                        nodeRoutes[j][route] -> d
                        else -> (((nodeWidth[j] + w) / 2 + gapDp) * upd).coerceIn(d, dMax)
                    }
                    if (dist >= thr) continue
                    if (j != last && j in recentSet) continue
                    val an = hypot(ax[j], ay[j])
                    if (an > 0 && (ax[j] * c2 + ay[j] * s2) / an < 0.5) continue // more than 30° apart
                    bd = dist; best = j
                }
            }
            if (best < 0) best = newNode(px[i], py[i], c2, s2, rank) else absorb(best, px[i], py[i], c2, s2, rank)
            sampleNode[i] = best
            if (best == last) continue
            fun push(v: Int) {
                seq.add(v); addRoute(v, route); recent.addLast(v); recentSet[v] = (recentSet[v] ?: 0) + 1
                if (recent.size > window) {
                    val o = recent.removeFirst(); val c = recentSet.getValue(o) - 1
                    if (c == 0) recentSet.remove(o) else recentSet[o] = c
                }
            }
            if (last >= 0 && !adj[last].contains(best)) {
                val path = shortPath(last, best)
                if (path != null) for (v in path) push(v) else link(last, best)
            }
            push(best)
        }
        for (i in 1 until seq.size) link(seq[i - 1], seq[i])
        return seq.toArray() to IntArray(quayAt.size) { sampleNode[quaySample[it]] }
    }

    /** Snaps every drawn run of every shown pattern: trams first, then Busway/Chronobus, then the longest patterns. */
    private fun snap(rules: HopRules, log: (String) -> Unit) {
        fun weight(p: Int): Int = 2 - style.rank(net.patterns[p].route)
        val order = rules.shown.sortedWith(compareBy<Int> { weight(it) }.thenByDescending { net.patterns[it].quays.size })
        val shapes = net.rideShapes
        for (p in order) {
            val pat = net.patterns[p]
            var a = 0
            while (a < pat.quays.size - 1) {
                if (!rules.drawn(pat, a)) { a++; continue }
                var b = a
                while (b < pat.quays.size - 1 && rules.drawn(pat, b)) b++
                // Positions a..b, every hop drawn: the quays and their hops' street paths.
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                val quayAt = IntArray(b - a + 1)
                for (pos in a..b) {
                    val qi = pat.quays[pos]
                    quayAt[pos - a] = xs.size
                    xs += WebMercator.x(net.quayLon[qi]); ys += WebMercator.y(net.quayLat[qi])
                    if (pos == b || shapes == null) continue
                    val h = shapes.indexOf(net.quayNumber[qi], net.quayNumber[pat.quays[pos + 1]])
                    if (h < 0) continue
                    val pts = shapes.interior(h)
                    for (j in 0 until pts.size / 2) { xs += WebMercator.x(pts[2 * j + 1]); ys += WebMercator.y(pts[2 * j]) }
                }
                val (seq, qn) = insert(xs.toDoubleArray(), ys.toDoubleArray(), quayAt, pat.route)
                seqs += seq; seqRoute += pat.route
                for (pos in a..b) {
                    quayNode.getOrPut(pat.quays[pos]) { IntList(2) }.add(qn[pos - a])
                    quaySeqs.getOrPut(pat.quays[pos]) { IntList(2) }.add(seqs.size - 1)
                }
                a = b
            }
        }
        if (rules.straightDropped.isNotEmpty()) log("straight hops not drawn: ${rules.straightDropped.sorted()}")
    }

    // ------------------------------------------------------------------------------------------
    // Union-find
    // ------------------------------------------------------------------------------------------

    private fun find(a: Int): Int {
        var x = a
        while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x] }
        return x
    }

    /**
     * Merges two node clusters.
     *
     * - **Two stations never merge**: neighbouring hubs (Commerce, Foch...) chained into one star of
     *   the whole centre at z12.
     * - [bounded] (short-segment contraction): refused when the cluster's bounding box would get wider
     *   than half the maximum merge distance. Chains of small merges otherwise drag a node hundreds of
     *   metres across a hub.
     * - The higher-ranked cluster keeps its position (trams stay on their tracks, a station where it
     *   was placed); equal ranks meet at their weighted mean.
     */
    private fun union(a: Int, b: Int, bounded: Boolean): Boolean {
        val ra = find(a); val rb = find(b)
        if (ra == rb) return false
        if (nodeRank[ra] == HUB_RANK && nodeRank[rb] == HUB_RANK) return false
        val x0 = min(bx0[ra], bx0[rb]); val x1 = max(bx1[ra], bx1[rb]); val y0 = min(by0[ra], by0[rb]); val y1 = max(by1[ra], by1[rb])
        if (bounded && hypot(x1 - x0, y1 - y0) > 0.5 * dMax) return false
        bx0[ra] = x0; bx1[ra] = x1; by0[ra] = y0; by1[ra] = y1
        when {
            nodeRank[rb] > nodeRank[ra] -> { nx[ra] = nx[rb]; ny[ra] = ny[rb]; nw[ra] = nw[rb]; nodeRank[ra] = nodeRank[rb] }
            nodeRank[rb] == nodeRank[ra] -> {
                val w = nw[ra] + nw[rb]
                nx[ra] = (nx[ra] * nw[ra] + nx[rb] * nw[rb]) / w; ny[ra] = (ny[ra] * nw[ra] + ny[rb] * nw[rb]) / w; nw[ra] = w
            }
        }
        uf[rb] = ra
        return true
    }

    // ------------------------------------------------------------------------------------------
    // 3. Stations
    // ------------------------------------------------------------------------------------------

    /**
     * Station hubs: a stop with at least [HUB_MIN_LINES] lines drawn and quays spread at least
     * [HUB_MIN_SPREAD_M] from their centre (bus stations, terminal loops around them) becomes one
     * junction: its lines meet there and the ones ending there stop there, as on the official map,
     * instead of a mesh of short segments around the quays and the loops. Every node within the
     * quays' spread + [HUB_MARGIN_M] (at most [HUB_MAX_RADIUS_M]) joins it.
     *
     * Its position is the plain mean of its best-ranked members (its trams, else Busway/Chronobus),
     * read before any union moves a node: Pirmil sits on the tram line, not at the centre of its
     * quays, which bent the trams towards the bus station. A hub without them stays at its quays'
     * centre. (The mean of the merged nodes instead drifted with each union and nearby hubs merged:
     * z12's centre became a star.) The hub then outranks every line ([HUB_RANK]) so nothing moves it.
     */
    private fun placeHubs(shown: List<Int>) {
        val servedBy = HashMap<Int, MutableSet<Int>>()
        for (p in shown) for (q in net.patterns[p].quays) servedBy.getOrPut(net.quayStopPlace[q]) { HashSet() } += net.patterns[p].route
        val nodeGrid = HashMap<Long, IntList>()
        val hubCell = HUB_MAX_RADIUS_M * k
        for (v in 0 until nn) nodeGrid.getOrPut(pairKey(floor(nx[v] / hubCell).toInt(), floor(ny[v] / hubCell).toInt())) { IntList() }.add(v)
        for ((sp, stop) in net.stopPlaces.withIndex()) {
            if ((servedBy[sp]?.size ?: 0) < HUB_MIN_LINES) continue
            val qx = stop.quays.map { WebMercator.x(net.quayLon[it]) }; val qy = stop.quays.map { WebMercator.y(net.quayLat[it]) }
            val cx = qx.average(); val cy = qy.average()
            val spread = qx.indices.maxOf { hypot(qx[it] - cx, qy[it] - cy) }
            if (spread < HUB_MIN_SPREAD_M * k) continue
            val radius = min(HUB_MAX_RADIUS_M * k, spread + HUB_MARGIN_M * k)
            val gx = floor(cx / hubCell).toInt(); val gy = floor(cy / hubCell).toInt()
            val members = IntList()
            for (x in gx - 1..gx + 1) for (y in gy - 1..gy + 1) {
                val list = nodeGrid[pairKey(x, y)] ?: continue
                for (t in 0 until list.size) {
                    val v = list[t]
                    // A node already in a hub stays there: hubs placed on their trams would otherwise chain.
                    if (nodeRank[find(v)] == HUB_RANK || hypot(nx[v] - cx, ny[v] - cy) > radius) continue
                    members.add(v)
                }
            }
            if (members.size == 0) continue
            var best = 0
            for (t in 0 until members.size) best = max(best, nodeRank[members[t]])
            var hx = cx; var hy = cy
            if (best > 0) {
                var sx = 0.0; var sy = 0.0; var c = 0
                for (t in 0 until members.size) { val v = members[t]; if (nodeRank[v] == best) { sx += nx[v]; sy += ny[v]; c++ } }
                hx = sx / c; hy = sy / c
            }
            val first = members[0]
            for (t in 1 until members.size) union(first, members[t], false)
            hubSeeds.add(first); hubStops[sp] = first
            val r = find(first)
            nx[r] = hx; ny[r] = hy; nw[r] = 1e9; nodeRank[r] = HUB_RANK
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. Clean-up rounds
    // ------------------------------------------------------------------------------------------

    /**
     * Up to [MAX_ROUNDS] rounds, each on the current [Topology], until a round changes nothing:
     *
     * 1. **Short segments** (under half their bundle's width, the junction radius) contract to one
     *    node: junction clusters and snapping wobbles. One contraction per node and round, bounded
     *    ([union]), so neighbouring short segments don't chain across a hub.
     * 2. **Twins**: parallel segments between the same two nodes merge onto the one with most lines,
     *    when they are within merge distance. A bare chord beside a path is rerouted along the path
     *    ([rewrites]) when the path stays near it (else they are two streets: Rue de l'Île Macé and
     *    Bd Général de Gaulle).
     * 3. **Bubbles** ([mergeBubbles]): two ways of up to three segments between the same junctions.
     * 4. **Overlaps** ([mergeOverlaps]): corridors running side by side closer than their bundles are
     *    wide. Never into a station: it outranks every line, so the corridor alongside would jump onto
     *    it (Place du Cirque, 250 m onto St-Nicolas at z13, the trams tilted with it).
     * 5. **Tiny loops**: a closed segment shorter than pi x its bundle width can't be drawn as a loop,
     *    it is a knot; its lines go straight through its junction instead (Haluchère - Batignolles:
     *    what was left of the bus station loops, 170 m round for tram 1 and four buses). A
     *    junction-radius contraction was tried first and did nothing there.
     * 6. **Roundabouts** ([contractRoundabouts]).
     */
    private fun cleanUp(log: (String) -> Unit): List<IntArray> {
        var current = seqs.map { cleanSequence(it) }
        for (round in 0 until MAX_ROUNDS) {
            val topo = Topology(current, seqRoute, nx, ny)
            var changes = 0
            val touched = HashSet<Int>()
            for ((si, s) in topo.segments.withIndex()) {
                val short = max(d, min(0.5 * style.bundleWidth(topo.segRoutes[si].toIntArray(), z) * upd, 0.5 * dMax))
                if (topo.length(s) >= short || s.first() == s.last()) continue
                if (s.any { it in touched }) continue
                touched.addAll(s.asIterable())
                var c = false
                for (v in s) c = union(s.first(), v, true) || c
                if (c) changes++
            }
            changes += mergeTwins(topo)
            val hubRoots = HashSet<Int>().also { h -> for (i in 0 until hubSeeds.size) h += find(hubSeeds[i]) }
            // The stops each node serves, and each hub's own stop: a way serving another stop is a street.
            val nodeStops = HashMap<Int, MutableSet<Int>>()
            for ((q, list) in quayNode) for (i in 0 until list.size) nodeStops.getOrPut(find(list[i])) { HashSet() } += net.quayStopPlace[q]
            val hubStopAt = HashMap<Int, Int>().also { h -> for ((sp, v) in hubStops) h[find(v)] = sp }
            fun ownWay(hub: Int, nodes: IntArray): Boolean {
                val own = hubStopAt[hub]
                return (1 until nodes.size - 1).all { i -> nodeStops[nodes[i]].orEmpty().all { it == own } }
            }
            changes += mergeBubbles(topo, hubRoots, ::ownWay)
            val afterTwins = changes
            changes += mergeOverlaps(topo) { a, b -> nodeRank[find(a)] != HUB_RANK && nodeRank[find(b)] != HUB_RANK && union(a, b, false) }
            val tinyLoops = HashMap<Int, Set<Int>>()
            for ((si, sg) in topo.segments.withIndex()) {
                if (sg.size < 3 || sg.first() != sg.last()) continue
                if (topo.length(sg) < PI * style.bundleWidth(topo.segRoutes[si].toIntArray(), z) * upd) {
                    tinyLoops[sg.first()] = (tinyLoops[sg.first()] ?: emptySet()) + sg.copyOfRange(1, sg.size - 1).toSet()
                }
            }
            changes += tinyLoops.size
            changes += contractRoundabouts(topo)
            log(
                "round $round: ${topo.segments.size} segments, ${topo.segments.sumOf { it.size }} segment nodes, " +
                    "$afterTwins short/twin/bubble + ${changes - afterTwins} overlap/loop/roundabout merges",
            )
            if (changes == 0) break
            current = current.map { cleanSequence(cutLoops(rewrite(cleanSequence(it)), tinyLoops)) }
        }
        return current
    }

    private fun mergeTwins(topo: Topology): Int {
        var changes = 0
        val byEnds = topo.segments.indices.groupBy { edgeKey(topo.segments[it].first(), topo.segments[it].last()) }
        for ((_, group) in byEnds) {
            if (group.size < 2) continue
            val base = group.maxBy { topo.segRoutes[it].cardinality() * 1e6 + topo.segments[it].size }
            val bs = topo.segments[base]
            for (o in group) {
                if (o == base) continue
                val os = topo.segments[o]
                if (os.size == 2) {
                    val farPath = bs.maxOf { v -> segDist(nx[v], ny[v], nx[os[0]], ny[os[0]], nx[os[1]], ny[os[1]]) }
                    if (bs.size > 2 && farPath <= mergeLimit(topo.segRoutes[base], topo.segRoutes[o]) &&
                        rewrites.put(edgeKey(os[0], os[1]), bs) == null
                    ) changes++
                    continue
                }
                val lo = topo.length(os); val lb = topo.length(bs)
                if (max(lo, lb) > 1.6 * min(lo, lb) + d) continue
                var far = 0.0
                val nearest = IntArray(os.size) { i ->
                    var bj = bs[0]; var bdist = Double.MAX_VALUE
                    for (v in bs) { val dd = hypot(nx[v] - nx[os[i]], ny[v] - ny[os[i]]); if (dd < bdist) { bdist = dd; bj = v } }
                    far = max(far, bdist); bj
                }
                if (far > mergeLimit(topo.segRoutes[base], topo.segRoutes[o])) continue
                var c = false
                for (i in 1 until os.size - 1) c = union(nearest[i], os[i], false) || c
                if (c) changes++
            }
        }
        return changes
    }

    /** A sequence with every rerouted chord ([rewrites]) replaced by the path its lines take instead. */
    private fun rewrite(seq: IntArray): IntArray {
        if (rewrites.isEmpty()) return seq
        val out = IntList(seq.size)
        for (i in seq.indices) {
            if (i > 0) {
                val u = find(seq[i - 1]); val v = find(seq[i])
                val chain = rewrites[edgeKey(u, v)]
                if (chain != null) {
                    val c = IntArray(chain.size) { find(chain[it]) }
                    when {
                        c.first() == u && c.last() == v -> for (j in 1 until c.size - 1) out.add(c[j])
                        c.first() == v && c.last() == u -> for (j in c.size - 2 downTo 1) out.add(c[j])
                    }
                }
            }
            out.add(seq[i])
        }
        return out.toArray()
    }

    /**
     * Corridors laid down one line at a time can end up side by side closer than their bundles are
     * wide (the trams first, then the buses on the parallel carriageway just out of reach): the nodes
     * of a segment that lie within [mergeLimit] of another segment's node, same axis (within ~30°), for
     * at least [MIN_OVERLAP_RUN] consecutive nodes, are merged onto that segment. Narrow segments
     * first, one to one (a node takes at most one other, and a node another merged onto is not merged
     * away itself: else both chains collapse into a few nodes).
     *
     * **Sideways only**: a candidate further along the corridor than [d] is skipped. A node far along
     * would drag the other there: Place du Cirque was pulled onto a tram node 120 m down Cours des 50
     * Otages at z13, and the trams at Commerce tilted from about 30° to 45° with it.
     */
    private fun mergeOverlaps(topo: Topology, union: (Int, Int) -> Boolean): Int {
        val segs = topo.segments
        val width = DoubleArray(segs.size) { style.bundleWidth(topo.segRoutes[it].toIntArray(), z) * upd }
        fun c(v: Double) = floor(v / dMax).toLong()
        val grid = HashMap<Long, ArrayList<Long>>()
        for ((s, nodes) in segs.withIndex()) for (i in nodes.indices) {
            val v = nodes[i]
            grid.getOrPut(pairKey(c(nx[v]).toInt(), c(ny[v]).toInt())) { ArrayList() } += pairKey(s, i)
        }
        fun axis(s: Int, i: Int): Pair<Double, Double> {
            val n = segs[s]
            val a = n[max(0, i - 2)]; val b = n[min(n.size - 1, i + 2)]
            val tx = nx[b] - nx[a]; val ty = ny[b] - ny[a]
            val l = hypot(tx, ty).takeIf { it > 0 } ?: 1.0
            return tx / l to ty / l
        }
        var merges = 0
        val targets = HashSet<Int>(); val sources = HashSet<Int>()
        for (s in segs.indices.sortedBy { width[it] }) {
            val nodes = segs[s]
            if (nodes.size < 3) continue
            val match = IntArray(nodes.size) { -1 }
            var count = 0
            for (i in 1 until nodes.size - 1) {
                val v = nodes[i]
                val (tx, ty) = axis(s, i)
                var best = -1; var bd = Double.MAX_VALUE
                val cx = c(nx[v]); val cy = c(ny[v])
                for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) for (packed in grid[pairKey(gx.toInt(), gy.toInt())] ?: continue) {
                    val o = (packed ushr 32).toInt(); val j = packed.toInt()
                    if (o == s) continue
                    val u = segs[o][j]
                    if (u == v) continue
                    val dist = hypot(nx[u] - nx[v], ny[u] - ny[v])
                    if (dist >= bd || dist >= mergeLimit(topo.segRoutes[s], topo.segRoutes[o])) continue
                    val (ux, uy) = axis(o, j)
                    if (abs(tx * ux + ty * uy) < 0.85) continue
                    if (abs((nx[u] - nx[v]) * tx + (ny[u] - ny[v]) * ty) > d) continue
                    bd = dist; best = u
                }
                if (best >= 0) { match[i] = best; count++ }
            }
            val need = min(MIN_OVERLAP_RUN, nodes.size - 2)
            if (count < need) continue
            // Runs of consecutive matched nodes: a long segment may run alongside another for part of it only.
            var changed = false
            var i = 0
            while (i < nodes.size) {
                if (match[i] < 0) { i++; continue }
                var j = i
                while (j + 1 < nodes.size && match[j + 1] >= 0) j++
                if (j - i + 1 >= need) for (t in i..j) {
                    val u = match[t]; val v = nodes[t]
                    if (u in targets || u in sources || v in targets) continue
                    targets += u; sources += v
                    changed = union(u, v) || changed
                }
                i = j + 1
            }
            if (changed) merges++
        }
        return merges
    }

    /**
     * Bubbles: two ways between the same two junctions, at least one of them two or three segments
     * long (a single segment each is a twin, merged above), close enough that their bundles would
     * overlap. The way with fewer lines on average is folded onto the other: each of its nodes, the
     * junctions in between included, joins the nearest node of the other way; a bare chord is
     * rerouted along it instead.
     *
     * That is the bus bundle on the Pont de Pont-Rousseau beside the tram bridge at Pirmil: merged
     * with the trams in some places and not others, it made a chain of small loops, and lanes swapped
     * at each of them. LOOM settles it the same way, by folding whatever overlaps at the map's scale.
     *
     * - The merge distance is that of the lines along the **whole** way ([Way.through]): a line
     *   joining for one segment doesn't widen it (Busway 4 made Rue Gambetta and its parallel street,
     *   100 m apart, one).
     * - The two directions of the same lines going round a block or a roundabout by opposite sides
     *   merge up to [ROUNDABOUT_M] whatever their widths (a knot at the Pavillon roundabout).
     * - **At a station** the limit grows to the station's size (its bus station loops: buses running
     *   through the bus station beside trams passing it by are one way on the official map), but only
     *   for ways that stay within 2 x [HUB_MAX_RADIUS_M] of it and serve no other stop ([ownWay]):
     *   not Rue Gambetta and its parallel street leaving Foch, nor Place du Cirque 200 m from
     *   St-Nicolas (folded into the station at z13, it pulled the tram corridor round with it). And
     *   there, trams are never folded onto the buses' way (at Hôtel Dieu they would leave the Pirmil
     *   bridge for Clos Toreau). Ranking the bases globally instead made tram 2 absorb buses.
     */
    private fun mergeBubbles(topo: Topology, hubs: Set<Int>, ownWay: (Int, IntArray) -> Boolean): Int {
        val segsAt = HashMap<Int, MutableList<Int>>()
        for ((si, s) in topo.segments.withIndex()) {
            if (s.first() == s.last()) continue
            segsAt.getOrPut(s.first()) { ArrayList() } += si
            segsAt.getOrPut(s.last()) { ArrayList() } += si
        }
        val maxLen = 3 * dMax
        val used = HashSet<Int>()
        var changes = 0
        for (u in segsAt.keys) {
            val ways = HashMap<Int, MutableList<Way>>()
            fun walk(v: Int, segs: List<Int>, nodes: IntList, len: Double, seen: Set<Int>) {
                for (si in segsAt[v] ?: return) {
                    if (si in segs) continue
                    val s = topo.segments[si]
                    val w = if (s.first() == v) s.last() else s.first()
                    if (w in seen) continue
                    val l = len + topo.length(s)
                    if (l > maxLen) continue
                    val ns = IntList(nodes.size + s.size)
                    for (i in 0 until nodes.size) ns.add(nodes[i])
                    if (s.first() == v) for (i in 1 until s.size) ns.add(s[i]) else for (i in s.size - 2 downTo 0) ns.add(s[i])
                    val segs2 = segs + si
                    if (w > u) ways.getOrPut(w) { ArrayList() } += Way(topo, segs2, ns.toArray(), l)
                    if (segs2.size < 3) walk(w, segs2, ns, l, seen + w)
                }
            }
            walk(u, emptyList(), IntList().also { it.add(u) }, 0.0, setOf(u))
            for ((w, list) in ways) {
                if (list.size < 2) continue
                val hub = if (u in hubs) u else if (w in hubs) w else -1
                for (i in list.indices) for (j in i + 1 until list.size) {
                    val a = list[i]; val b = list[j]
                    val atHub = hub >= 0 &&
                        (a.nodes.asSequence() + b.nodes.asSequence()).all { hypot(nx[it] - nx[hub], ny[it] - ny[hub]) <= 2 * HUB_MAX_RADIUS_M * k } &&
                        ownWay(hub, a.nodes) && ownWay(hub, b.nodes)
                    if (a.segs.size == 1 && b.segs.size == 1) continue
                    if (a.segs.any { it in used } || b.segs.any { it in used }) continue
                    // Internally disjoint (two different ways, not one inside the other).
                    val inner = a.nodes.copyOfRange(1, a.nodes.size - 1).toHashSet()
                    if (b.segs.any { it in a.segs } || (1 until b.nodes.size - 1).any { b.nodes[it] in inner }) continue
                    // A station's ways in and out of the bus station run longer than the trams passing it by.
                    if (max(a.len, b.len) > (if (atHub) 2.5 else 1.6) * min(a.len, b.len) + d) continue
                    val (base, other) = if (a.weight >= b.weight) a to b else b to a
                    if (atHub && other.rank > base.rank) continue
                    val bn = base.nodes
                    var far = 0.0
                    val nearest = IntArray(other.nodes.size) { t ->
                        val v = other.nodes[t]
                        var best = bn[0]; var bd = Double.MAX_VALUE
                        for (q in bn) { val dd = hypot(nx[q] - nx[v], ny[q] - ny[v]); if (dd < bd) { bd = dd; best = q } }
                        var pd = Double.MAX_VALUE
                        for (q in 1 until bn.size) pd = min(pd, segDist(nx[v], ny[v], nx[bn[q - 1]], ny[bn[q - 1]], nx[bn[q]], ny[bn[q]]))
                        far = max(far, pd); best
                    }
                    // And the other way round: a bare chord has no node of its own near a far base.
                    val on = other.nodes
                    for (q in bn) {
                        var pd = Double.MAX_VALUE
                        for (r in 1 until on.size) pd = min(pd, segDist(nx[q], ny[q], nx[on[r - 1]], ny[on[r - 1]], nx[on[r]], ny[on[r]]))
                        far = max(far, pd)
                    }
                    val limit = if (a.routes == b.routes) max(mergeLimit(base.through, other.through), ROUNDABOUT_M * k)
                    else mergeLimit(base.through, other.through)
                    if (far > (if (atHub) min(dMax, max(limit, 2 * HUB_MAX_RADIUS_M * k)) else limit)) continue
                    if (on.size == 2) {
                        if (rewrites.put(edgeKey(on[0], on[1]), bn) == null) { changes++; used += a.segs; used += b.segs }
                        continue
                    }
                    var c = false
                    for (t in 1 until other.nodes.size - 1) c = union(nearest[t], other.nodes[t], false) || c
                    if (c) { changes++; used += a.segs; used += b.segs }
                }
            }
        }
        return changes
    }

    /** A way of one to three segments between two junctions (see [mergeBubbles]). */
    private inner class Way(topo: Topology, val segs: List<Int>, val nodes: IntArray, val len: Double) {
        val routes = BitSet().also { b -> segs.forEach { b.or(topo.segRoutes[it]) } }

        /** The lines along the whole way. */
        val through = (topo.segRoutes[segs[0]].clone() as BitSet).also { b -> segs.forEach { b.and(topo.segRoutes[it]) } }

        /** Mean number of lines along the way. */
        val weight = segs.sumOf { topo.segRoutes[it].cardinality() * topo.length(topo.segments[it]) } / max(len, 1e-9)

        /** Its best line's rank. */
        val rank = routes.stream().map { style.rank(it) }.max().orElse(0)
    }

    /**
     * Roundabouts: a cycle of 2 to 6 segments that is round (every node within [ROUNDABOUT_RADIUS_M]
     * of its centre, none closer than 40 % of the farthest) becomes one junction at its centre, as on
     * the official map. Its lines took one half or the other by direction, and at every junction round
     * the ring their lanes swapped and curled: a knot (Place Mellinet). The thresholds come from
     * Mellinet itself, measured at 23 to 52 m from its centre. A terminus loop round a block isn't
     * round and is kept; a small roundabout where one direction cuts the corner to its stop may stay
     * split (Armoricaine), which is accepted: buses do go round, not through the middle.
     */
    private fun contractRoundabouts(topo: Topology): Int {
        val segsAt = HashMap<Int, MutableList<Int>>()
        for ((si, sg) in topo.segments.withIndex()) {
            if (sg.first() == sg.last()) continue
            segsAt.getOrPut(sg.first()) { ArrayList() } += si
            segsAt.getOrPut(sg.last()) { ArrayList() } += si
        }
        val maxR = ROUNDABOUT_RADIUS_M * k
        val maxLen = 2 * PI * maxR * 1.3
        val used = HashSet<Int>()
        var changes = 0
        for (start in segsAt.keys.sorted()) {
            if (start in used) continue
            var found: Triple<List<Int>, Double, Double>? = null
            // Every cycle through start is checked, not only the first: a triangle at a junction isn't
            // round, and stopping at it missed Mellinet.
            fun round(cycle: List<Int>): Triple<List<Int>, Double, Double>? {
                val nodes = cycle.flatMap { topo.segments[it].asIterable() }.distinct()
                if (nodes.any { it in used }) return null
                val cx = nodes.sumOf { nx[it] } / nodes.size; val cy = nodes.sumOf { ny[it] } / nodes.size
                val r = nodes.map { hypot(nx[it] - cx, ny[it] - cy) }
                return if (r.max() > maxR || r.min() < 0.4 * r.max()) null else Triple(nodes, cx, cy)
            }
            fun dfs(v: Int, path: List<Int>, len: Double, seen: Set<Int>) {
                if (found != null) return
                for (si in segsAt[v] ?: return) {
                    if (found != null) return
                    if (si in path) continue
                    val sg = topo.segments[si]
                    val w = if (sg.first() == v) sg.last() else sg.first()
                    val l = len + topo.length(sg)
                    if (l > maxLen) continue
                    if (w == start) { if (path.isNotEmpty()) found = round(path + si); continue }
                    if (w in seen || w < start || path.size >= 5) continue
                    dfs(w, path + si, l, seen + w)
                }
            }
            dfs(start, emptyList(), 0.0, setOf(start))
            val (nodes, cx, cy) = found ?: continue
            var c = false
            for (v in nodes) c = union(nodes[0], v, false) || c
            if (!c) continue
            val r = find(nodes[0]); nx[r] = cx; ny[r] = cy
            used += nodes; changes++
        }
        return changes
    }

    /**
     * How far apart (Mercator metres) two corridors carrying [a] and [b] may be merged: where their
     * bundles would overlap as drawn; or, when one's lines are all in the other (the two directions,
     * or two variants, of the same lines), up to [SAME_LINES_MERGE_DP]: one line at a far zoom, two
     * where the close-up has room.
     */
    private fun mergeLimit(a: BitSet, b: BitSet): Double {
        val overlap = (style.bundleWidth(a.toIntArray(), z) + style.bundleWidth(b.toIntArray(), z)) / 2 + gapDp
        val ab = a.clone() as BitSet; ab.andNot(b)
        val ba = b.clone() as BitSet; ba.andNot(a)
        val nested = ab.isEmpty || ba.isEmpty
        return min(dMax, max(d, (if (nested) max(overlap, SAME_LINES_MERGE_DP) else overlap) * upd))
    }

    /** Drops the passes round [loops] (anchor -> interior nodes): a, x, y, a -> a. */
    private fun cutLoops(seq: IntArray, loops: Map<Int, Set<Int>>): IntArray {
        if (loops.isEmpty()) return seq
        val out = IntList(seq.size)
        var i = 0
        while (i < seq.size) {
            val v = seq[i]
            out.add(v)
            val inner = loops[v]
            if (inner != null) {
                var j = i + 1
                while (j < seq.size && seq[j] in inner) j++
                if (j > i + 1 && j < seq.size && seq[j] == v) { i = j + 1; continue }
            }
            i++
        }
        return out.toArray()
    }

    /** Remaps through [find], drops repeats and there-and-back spurs (a, b, a -> a). */
    private fun cleanSequence(seq: IntArray): IntArray {
        val out = IntList(seq.size)
        for (v0 in seq) {
            val v = find(v0)
            if (out.size > 0 && out.last() == v) continue
            if (out.size >= 2 && out[out.size - 2] == v) { out.size--; continue }
            out.add(v)
        }
        return out.toArray()
    }

    // ------------------------------------------------------------------------------------------
    // 5. Segments, markers, lane order
    // ------------------------------------------------------------------------------------------

    private fun finish(current: List<IntArray>, log: (String) -> Unit): LineMap {
        val topo = Topology(current, seqRoute, nx, ny)
        // Geometry: segment interiors smoothed lightly (ends fixed), written back so stops sit on the
        // drawn line. One pass over three points: the nodes are means already, more would cut corners
        // (a line's zigzag to a stop).
        val px = nx.copyOf(nn); val py = ny.copyOf(nn)
        for (s in topo.segments) {
            if (s.size < 3) continue
            for (i in 1 until s.size - 1) {
                px[s[i]] = (nx[s[i - 1]] + nx[s[i]] + nx[s[i + 1]]) / 3
                py[s[i]] = (ny[s[i - 1]] + ny[s[i]] + ny[s[i + 1]]) / 3
            }
        }
        val segments = topo.segments.mapIndexed { si, s ->
            val keep = simplify(s.map { px[it] }, s.map { py[it] }, 0.25 * upd)
            LineMap.Segment(s.first(), s.last(), DoubleArray(keep.size) { px[s[keep[it]]] }, DoubleArray(keep.size) { py[s[keep[it]]] }, topo.segRoutes[si].toIntArray())
        }
        val transitions = topo.transitions(current, seqRoute)
        val stops = markers(topo, current, px, py, log)
        val pinned = pins(segments)
        // Only junctions (segment ends) are kept as nodes, renumbered densely.
        val ids = HashMap<Int, Int>()
        val keptX = ArrayList<Double>(); val keptY = ArrayList<Double>()
        fun id(v: Int) = ids.getOrPut(v) { keptX += px[v]; keptY += py[v]; ids.size }
        val renumbered = segments.map { LineMap.Segment(id(it.first), id(it.last), it.x, it.y, it.order) }
        val map = LineMap(
            zoom, routes, keptX.toDoubleArray(), keptY.toDoubleArray(), renumbered,
            transitions.map { LineMap.Transition(id(it.node), it.endA, it.endB, it.route) }, stops,
        )
        val orderer = LaneOrderer(map, pinned)
        val before = orderer.totalCost()
        orderer.barycentre()
        val afterBarycentre = orderer.totalCost()
        orderer.optimise()
        log("lane order: $before -> $afterBarycentre (barycentre) -> ${orderer.totalCost()} crossings")
        return map
    }

    /**
     * Stop markers, on the drawn lines (not at the quays: with lanes side by side, a dot at the quay
     * position would sit beside the line).
     *
     * - A station sits on its hub's junction, where its lines meet (not the node nearest its quays'
     *   centre, which can be a lone line passing by).
     * - Otherwise a stop's quays are grouped: two quays share a marker when a line stopping at one
     *   passes the other (the two sides of a street, staggered stops on one corridor) or when they are
     *   within [SPLIT_STOP_DP] dp at this zoom (too close to read as two). Each group gets one marker,
     *   so a stop whose directions run on different streets far enough apart gets one on each (6 stops
     *   at z12, 18 at z13, 51 at z14).
     * - A group's marker is the node nearest its quays' mean among **all** the nodes of the segments
     *   its quays are on: staggered quays (Mont Blanc, 110 m apart along one road) get the dot between
     *   them, not at one end.
     */
    private fun markers(topo: Topology, current: List<IntArray>, px: DoubleArray, py: DoubleArray, log: (String) -> Unit): List<LineMap.StopMarker> {
        val onSegment = BooleanArray(nn).also { b -> for (s in topo.segments) for (v in s) b[v] = true }
        val segNodes = topo.segments.flatMap { it.asIterable() }.distinct()
        val segsOfNode = HashMap<Int, IntList>()
        for ((si, sg) in topo.segments.withIndex()) for (v in sg) segsOfNode.getOrPut(v) { IntList(2) }.add(si)
        fun markerNode(nodes: List<Int>): Int {
            if (nodes.isEmpty()) return -1
            val mx = nodes.sumOf { px[it] } / nodes.size; val my = nodes.sumOf { py[it] } / nodes.size
            val on = nodes.filter { onSegment[it] }.flatMap { v -> segsOfNode[v]?.toArray()?.flatMap { topo.segments[it].asIterable() }.orEmpty() }
            val pool = on.ifEmpty { segNodes.filter { hypot(px[it] - mx, py[it] - my) < dMax } }
            return pool.minByOrNull { hypot(px[it] - mx, py[it] - my) } ?: -1
        }
        val seqNodes = HashMap<Int, Set<Int>>()
        fun nodesOf(seq: Int) = seqNodes.getOrPut(seq) { current[seq].toHashSet() }
        val splitDist = SPLIT_STOP_DP * upd
        val stops = ArrayList<LineMap.StopMarker>()
        var split = 0
        for (sp in net.stopPlaces.indices) {
            val quays = net.stopPlaces[sp].quays.filter { quayNode[it] != null }
            if (quays.isEmpty()) continue
            val id = net.stopPlaces[sp].id
            fun nodes(q: Int) = quayNode.getValue(q).toArray().map(::find)
            if (sp in hubStops) {
                val h = find(hubStops.getValue(sp))
                val node = if (onSegment[h]) h else markerNode(listOf(h))
                if (node < 0) continue
                stops += hubMarker(id, node, topo, px, py)
                continue
            }
            val group = IntArray(quays.size) { it }
            fun root(i: Int): Int { var x = i; while (group[x] != x) x = group[x]; return x }
            for (a in quays.indices) for (b in a + 1 until quays.size) {
                if (root(a) == root(b)) continue
                val na = nodes(quays[a]); val nb = nodes(quays[b])
                val near = na.any { u -> nb.any { v -> hypot(px[u] - px[v], py[u] - py[v]) <= splitDist } }
                fun passes(from: Int, to: List<Int>) = quaySeqs.getValue(from).toArray().any { sq -> to.any { it in nodesOf(sq) } }
                if (near || passes(quays[a], nb) || passes(quays[b], na)) group[root(b)] = root(a)
            }
            val groups = quays.indices.groupBy(::root).values
            if (groups.size > 1) split++
            for (g in groups) {
                val node = markerNode(g.flatMap { nodes(quays[it]) })
                if (node >= 0) stops += LineMap.StopMarker(id, px[node], py[node], false)
            }
        }
        log("stop markers: ${stops.size}, $split stops with a marker per direction")
        return stops
    }

    /**
     * A station's marker: its dash goes across the widest bundle through the hub (as long as that
     * bundle is wide, so it reads as one stop for all of them), perpendicular to that bundle's
     * direction at the hub.
     */
    private fun hubMarker(id: String, node: Int, topo: Topology, px: DoubleArray, py: DoubleArray): LineMap.StopMarker {
        var best = -1; var bw = -1.0; var at = 0
        for ((si, s) in topo.segments.withIndex()) {
            val i = s.indexOf(node)
            if (i < 0) continue
            val w = style.bundleWidth(topo.segRoutes[si].toIntArray(), z)
            if (w > bw) { bw = w; best = si; at = i }
        }
        if (best < 0) return LineMap.StopMarker(id, px[node], py[node], true)
        val s = topo.segments[best]
        val a = s[max(0, at - 1)]; val b = s[min(s.size - 1, at + 1)]
        return LineMap.StopMarker(id, px[node], py[node], true, best, atan2(py[b] - py[a], px[b] - px[a]))
    }

    /**
     * Manual adjustment ([TRAMS_WEST_HUBS]): at Pirmil the trams keep the west side of every bundle
     * round the station and the other lines are ordered around them, as on the official map. Left to
     * the crossing count, the trams took the middle of the bundle to Pont-Rousseau and the lines from
     * the east split round them (C4 over the river, a tangle of curls at z13). Freezing the whole
     * order there was tried first: it froze the buses too and TE1/E8 crossed the whole bundle, so the
     * pin only moves the trams. Pirmil is the network's one exceptional hub (tram terminus, bus
     * station and bridge); Commerce is the only other candidate if it ever needs one.
     */
    private fun pins(segments: List<LineMap.Segment>): Map<Int, (IntArray) -> IntArray> {
        val pinned = HashMap<Int, (IntArray) -> IntArray>()
        for ((sp, v) in hubStops) {
            if (net.stopPlaces[sp].name !in TRAMS_WEST_HUBS) continue
            val hub = find(v)
            for ((si, seg) in segments.withIndex()) {
                if (seg.first != hub && seg.last != hub) continue
                if (seg.order.none { style.kind[it] == LineStyle.Kind.TRAM }) continue
                // order[0] is the left lane travelling first -> last: trams first when left is west,
                // which is when the segment runs northwards.
                val leftIsWest = seg.y.last() - seg.y.first() > 0
                pinned[si] = { order ->
                    val trams = order.filter { style.kind[it] == LineStyle.Kind.TRAM }
                    val others = order.filter { style.kind[it] != LineStyle.Kind.TRAM }
                    (if (leftIsWest) trams + others else others + trams).toIntArray()
                }
                seg.order = pinned.getValue(si)(seg.order)
            }
        }
        return pinned
    }

    companion object {
        /** Merge distance floor (dp): both directions of a street, double tram tracks. */
        const val MIN_MERGE_DP = 8.0

        /** Merge distance ceiling (dp): beyond this, two corridors are two streets at any zoom. */
        const val MAX_MERGE_DP = 60.0

        /** Consecutive nodes of a segment alongside another one before they are merged ([mergeOverlaps]). */
        const val MIN_OVERLAP_RUN = 2

        /** Directions or variants of the same lines merge up to this far apart (dp, see [mergeLimit]). */
        const val SAME_LINES_MERGE_DP = 16.0

        /** A line's hop with fewer trips than this share of its busiest hop isn't drawn ([HopRules]). */
        const val MIN_HOP_SHARE = 0.15

        /** A way between two stops of a line carrying under this share of another way's trips is a detour ([HopRules]). */
        const val DETOUR_SHARE = 0.5

        /** A hop without street path longer than this (ground metres) isn't drawn, boats excepted ([HopRules]). */
        const val MAX_STRAIGHT_HOP_M = 400.0

        /** A station hub outranks every line: it stays where it was put ([placeHubs]). */
        const val HUB_RANK = 9

        /** Station hubs ([placeHubs]): at least this many lines drawn... */
        const val HUB_MIN_LINES = 5

        /** ...and quays spread at least this far from their centre (ground metres). */
        const val HUB_MIN_SPREAD_M = 40.0

        /** Nodes up to the quays' spread plus this margin join the hub (ground metres)... */
        const val HUB_MARGIN_M = 30.0

        /** ...but never farther than this (ground metres). */
        const val HUB_MAX_RADIUS_M = 120.0

        /** Stations where the trams keep the west side of every bundle (manual adjustment, see [pins]). */
        val TRAMS_WEST_HUBS = setOf("Pirmil")

        /** Two ways with the same lines between the same junctions merge up to this far apart (ground metres, [mergeBubbles]). */
        const val ROUNDABOUT_M = 50.0

        /** A round cycle of segments up to this radius (ground metres) is a roundabout: one junction ([contractRoundabouts]). */
        const val ROUNDABOUT_RADIUS_M = 70.0

        /** A stop's directions on different ways get a marker each when this far apart (dp at the layout zoom, [markers]). */
        const val SPLIT_STOP_DP = 16.0

        private const val MAX_ROUNDS = 16
    }
}

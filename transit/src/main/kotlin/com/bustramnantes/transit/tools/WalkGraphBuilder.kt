package com.bustramnantes.transit.tools

import com.bustramnantes.transit.walk.WalkGraph
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Builds the [WalkGraph] of Nantes Métropole from a Geofabrik `.osm.pbf` extract (the Pays de la Loire
 * one covers it). Build-time only, three streaming passes:
 *
 *  1. **relations** - the métropole's boundary relation (`ref:FR:SIREN=244400404`): its outer ways;
 *  2. **nodes** - coordinates of every node inside a generous box around Nantes (sorted ids + arrays);
 *  3. **ways** - the boundary ways' nodes, and every walkable way with a node in the box.
 *
 * Then: boundary rings assembled; walkable ways kept when one of their nodes lies inside the boundary
 * (a way is kept whole, which gives a natural buffer of a street or two beyond the limit); split at
 * junctions into edges; components smaller than [MIN_COMPONENT_METERS] dropped (isolated private
 * paths, footways inside a building plot, mapping gaps) so nothing snaps onto an island; shapes
 * simplified (Douglas–Peucker, [SIMPLIFY_METERS]); vertices ordered spatially.
 *
 * **Walkable** ([isWalkable]): the usual pedestrian profile - footways, paths, pedestrian streets,
 * steps, residential/service/minor and major roads (sidewalks are rarely mapped separately), tracks,
 * cycleways (pedestrians may use them in France unless signed otherwise); never motorways, roads
 * tagged `motorroad=yes` or `foot=no`, private/no access unless foot is explicitly allowed, or roads
 * under construction.
 */
object WalkGraphBuilder {
    const val NANTES_METROPOLE_SIREN = "244400404"
    private const val MIN_COMPONENT_METERS = 2_000.0
    private const val SIMPLIFY_METERS = 2.0

    // Box around the métropole (its extent is ~47.08..47.32 N, -1.83..-1.40 E), with margin.
    private const val BOX_LAT_MIN = 46.95
    private const val BOX_LAT_MAX = 47.45
    private const val BOX_LON_MIN = -2.0
    private const val BOX_LON_MAX = -1.25

    private val WALKABLE_HIGHWAYS = setOf(
        "footway", "path", "pedestrian", "steps", "living_street", "residential", "service", "track",
        "unclassified", "road", "tertiary", "tertiary_link", "secondary", "secondary_link", "primary",
        "primary_link", "trunk", "trunk_link", "cycleway", "bridleway", "corridor", "platform",
    )
    private val FOOT_ALLOWED = setOf("yes", "designated", "permissive", "destination")

    fun isWalkable(tags: Map<String, String>): Boolean {
        val highway = tags["highway"] ?: return false
        if (highway !in WALKABLE_HIGHWAYS) return false
        val foot = tags["foot"]
        if (foot == "no" || foot == "private" || foot == "use_sidepath") return false
        if (tags["motorroad"] == "yes" && foot !in FOOT_ALLOWED) return false
        val access = tags["access"]
        if ((access == "no" || access == "private") && foot !in FOOT_ALLOWED) return false
        if (tags["area"] == "yes" && highway != "pedestrian" && highway != "footway") return false
        return true
    }

    class Stats(val walkableWays: Int, val vertices: Int, val edges: Int, val droppedComponents: Int, val droppedMeters: Double, val totalMeters: Double)

    fun build(pbf: File, source: String, log: (String) -> Unit = ::println): Pair<WalkGraph, Stats> {
        val reader = OsmPbfReader(pbf)

        // 1. Boundary relation.
        var boundaryWays = LongArray(0)
        reader.read(object : OsmPbfReader.Visitor {
            override fun wantRelations() = true
            override fun relation(id: Long, tags: Map<String, String>, memberIds: LongArray, memberTypes: IntArray, roles: Array<String>) {
                if (tags["ref:FR:SIREN"] != NANTES_METROPOLE_SIREN || tags["type"] != "boundary") return
                log("boundary relation $id: ${tags["name"]} (${tags["boundary"]})")
                boundaryWays = memberIds.indices.filter { memberTypes[it] == 1 && roles[it] != "inner" }.map { memberIds[it] }.toLongArray()
            }
        })
        check(boundaryWays.isNotEmpty()) { "Nantes Métropole boundary relation not found in $pbf" }
        val boundaryWaySet = boundaryWays.toHashSet()

        // 2. Nodes in the box.
        val nodes = NodeStore()
        val latMin = (BOX_LAT_MIN * 1e7).toInt(); val latMax = (BOX_LAT_MAX * 1e7).toInt()
        val lonMin = (BOX_LON_MIN * 1e7).toInt(); val lonMax = (BOX_LON_MAX * 1e7).toInt()
        reader.read(object : OsmPbfReader.Visitor {
            override fun wantNodes() = true
            override fun node(id: Long, lat7: Int, lon7: Int) {
                if (lat7 in latMin..latMax && lon7 in lonMin..lonMax) nodes.add(id, lat7, lon7)
            }
        })
        nodes.seal()
        log("nodes in box: ${nodes.size}")

        // 3. Ways.
        val boundaryRefs = HashMap<Long, LongArray>()
        val ways = ArrayList<IntArray>() // node-store indices (-1 = outside the box)
        reader.read(object : OsmPbfReader.Visitor {
            override fun wantWays() = true
            override fun way(id: Long, tags: Map<String, String>, refs: LongArray) {
                if (id in boundaryWaySet) boundaryRefs[id] = refs
                if (!isWalkable(tags)) return
                val idx = IntArray(refs.size) { nodes.indexOf(refs[it]) }
                if (idx.any { it >= 0 }) ways += idx
            }
        })
        log("walkable ways in box: ${ways.size}")

        val rings = assembleRings(boundaryWays.toList().mapNotNull { boundaryRefs[it] }).map { ring ->
            val pts = ring.map { nodes.indexOf(it) }.filter { it >= 0 }
            DoubleArray(pts.size * 2) { k -> if (k % 2 == 0) nodes.lat(pts[k / 2]) / 1e7 else nodes.lon(pts[k / 2]) / 1e7 }
        }
        log("boundary rings: ${rings.size} (${rings.sumOf { it.size / 2 }} points)")
        val kept = ways.filter { w -> w.any { it >= 0 && insideAny(rings, nodes.lat(it) / 1e7, nodes.lon(it) / 1e7) } }
        log("walkable ways inside: ${kept.size}")

        return buildGraph(kept, nodes, source, log)
    }

    private fun buildGraph(ways: List<IntArray>, nodes: NodeStore, source: String, log: (String) -> Unit): Pair<WalkGraph, Stats> {
        // Junctions: nodes used twice or more (across or within ways), plus way ends and the
        // nodes next to a gap (a node outside the box).
        val uses = HashMap<Int, Int>()
        for (w in ways) for (n in w) if (n >= 0) uses.merge(n, 1, Int::plus)
        fun isVertex(w: IntArray, i: Int): Boolean =
            i == 0 || i == w.size - 1 || w[i - 1] < 0 || w[i + 1] < 0 || (uses[w[i]] ?: 0) >= 2

        val vertexOf = HashMap<Int, Int>()
        val vLat = ArrayList<Int>(); val vLon = ArrayList<Int>()
        fun vertex(n: Int): Int = vertexOf.getOrPut(n) { vLat += nodes.lat(n) / 10; vLon += nodes.lon(n) / 10; vLat.size - 1 }

        class E(val from: Int, val to: Int, val length: Double, val shape: List<Int>)
        val edges = ArrayList<E>()
        for (w in ways) {
            var segStart = -1
            var shape = ArrayList<Int>()
            var length = 0.0
            for (i in w.indices) {
                val n = w[i]
                if (n < 0) { segStart = -1; continue }
                if (segStart < 0) { segStart = i; shape = ArrayList(); length = 0.0; continue }
                length += meters(nodes, w[i - 1], n)
                if (isVertex(w, i)) {
                    if (length > 0) edges += E(vertex(w[segStart]), vertex(n), length, shape)
                    segStart = i; shape = ArrayList(); length = 0.0
                } else {
                    shape += n
                }
            }
        }

        // Components (union-find) by total length.
        val parent = IntArray(vLat.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        for (e in edges) { val a = find(e.from); val b = find(e.to); if (a != b) parent[a] = b }
        val compLength = HashMap<Int, Double>()
        for (e in edges) compLength.merge(find(e.from), e.length, Double::plus)
        val keepComp = compLength.filterValues { it >= MIN_COMPONENT_METERS }.keys
        val dropped = compLength.filterKeys { it !in keepComp }
        val keptEdges = edges.filter { find(it.from) in keepComp }
        log("components: ${compLength.size}, kept ${keepComp.size}; dropped ${dropped.size} (${"%.0f".format(dropped.values.sum())} m)")

        // Spatial vertex order (≈ 250 m cells, rows) for small deltas; drop unused vertices.
        val used = BooleanArray(vLat.size)
        for (e in keptEdges) { used[e.from] = true; used[e.to] = true }
        val cell = 2_500 // micro-degrees
        val order = (0 until vLat.size).filter { used[it] }
            .sortedWith(compareBy({ vLat[it] / cell }, { if ((vLat[it] / cell) % 2 == 0) vLon[it] else -vLon[it] }))
        val newIndex = IntArray(vLat.size) { -1 }
        order.forEachIndexed { i, v -> newIndex[v] = i }
        val lat = IntArray(order.size) { vLat[order[it]] }
        val lon = IntArray(order.size) { vLon[order[it]] }

        val from = IntArray(keptEdges.size); val to = IntArray(keptEdges.size); val len = FloatArray(keptEdges.size)
        val start = IntArray(keptEdges.size + 1)
        val gLat = ArrayList<Int>(); val gLon = ArrayList<Int>()
        keptEdges.forEachIndexed { i, e ->
            from[i] = newIndex[e.from]; to[i] = newIndex[e.to]; len[i] = e.length.toFloat()
            val pts = simplify(lat[from[i]], lon[from[i]], e.shape.map { nodes.lat(it) / 10 to nodes.lon(it) / 10 }, lat[to[i]], lon[to[i]])
            for ((a, b) in pts) { gLat += a; gLon += b }
            start[i + 1] = gLat.size
        }
        val graph = WalkGraph(source, lat, lon, from, to, len, start, gLat.toIntArray(), gLon.toIntArray())
        val stats = Stats(ways.size, lat.size, from.size, dropped.size, dropped.values.sum(), keptEdges.sumOf { it.length })
        return graph to stats
    }

    private fun meters(nodes: NodeStore, a: Int, b: Int): Double {
        val dLat = (nodes.lat(b) - nodes.lat(a)) / 1e7 * 111_195.0
        val dLon = (nodes.lon(b) - nodes.lon(a)) / 1e7 * 111_195.0 * cos(Math.toRadians(nodes.lat(a) / 1e7))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /** Douglas–Peucker over the intermediate points between two fixed ends (micro-degrees). */
    private fun simplify(aLat: Int, aLon: Int, mid: List<Pair<Int, Int>>, bLat: Int, bLon: Int): List<Pair<Int, Int>> {
        if (mid.isEmpty()) return mid
        val all = listOf(aLat to aLon) + mid + listOf(bLat to bLon)
        val keep = BooleanArray(all.size)
        keep[0] = true; keep[all.size - 1] = true
        val kx = 111_195.0 / 1e6
        val ky = kx * cos(Math.toRadians(aLat / 1e6))
        fun rec(i: Int, j: Int) {
            if (j <= i + 1) return
            val (x1, y1) = all[i]; val (x2, y2) = all[j]
            val sx = (x2 - x1) * kx; val sy = (y2 - y1) * ky
            val l2 = sx * sx + sy * sy
            var worst = -1; var worstD = 0.0
            for (k in i + 1 until j) {
                val px = (all[k].first - x1) * kx; val py = (all[k].second - y1) * ky
                val t = if (l2 == 0.0) 0.0 else ((px * sx + py * sy) / l2).coerceIn(0.0, 1.0)
                val dx = px - t * sx; val dy = py - t * sy
                val d = sqrt(dx * dx + dy * dy)
                if (d > worstD) { worstD = d; worst = k }
            }
            if (worstD > SIMPLIFY_METERS) { keep[worst] = true; rec(i, worst); rec(worst, j) }
        }
        rec(0, all.size - 1)
        return (1 until all.size - 1).filter { keep[it] }.map { all[it] }
    }

    /** Joins boundary ways end to end into closed rings (node-id lists). */
    internal fun assembleRings(parts: List<LongArray>): List<List<Long>> {
        val remaining = parts.map { it.toMutableList() }.toMutableList()
        val rings = ArrayList<List<Long>>()
        while (remaining.isNotEmpty()) {
            val ring = remaining.removeAt(0)
            var progress = true
            while (ring.first() != ring.last() && progress) {
                progress = false
                val it = remaining.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    when (ring.last()) {
                        p.first() -> { ring.addAll(p.drop(1)); it.remove(); progress = true }
                        p.last() -> { ring.addAll(p.reversed().drop(1)); it.remove(); progress = true }
                        else -> continue
                    }
                    if (ring.first() == ring.last()) break
                }
            }
            if (ring.size >= 4) rings += ring
        }
        return rings
    }

    /** Even-odd point-in-polygon over all rings (lat/lon interleaved). */
    internal fun insideAny(rings: List<DoubleArray>, lat: Double, lon: Double): Boolean {
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val yi = r[i * 2]; val xi = r[i * 2 + 1]; val yj = r[j * 2]; val xj = r[j * 2 + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Node coordinates by id (ids arrive sorted in Geofabrik extracts; sorted at [seal] otherwise). */
    private class NodeStore {
        private var ids = LongArray(1 shl 20)
        private var lats = IntArray(1 shl 20)
        private var lons = IntArray(1 shl 20)
        var size = 0
            private set

        fun add(id: Long, lat7: Int, lon7: Int) {
            if (size == ids.size) { ids = ids.copyOf(size * 2); lats = lats.copyOf(size * 2); lons = lons.copyOf(size * 2) }
            ids[size] = id; lats[size] = lat7; lons[size] = lon7; size++
        }

        fun seal() {
            var sorted = true
            for (i in 1 until size) if (ids[i] < ids[i - 1]) { sorted = false; break }
            if (!sorted) {
                val order = (0 until size).sortedBy { ids[it] }
                ids = LongArray(size) { ids[order[it]] }.also { a -> lats = IntArray(size) { lats[order[it]] }; lons = IntArray(size) { lons[order[it]] } }
            }
        }

        fun indexOf(id: Long): Int { val i = java.util.Arrays.binarySearch(ids, 0, size, id); return if (i >= 0) i else -1 }
        fun lat(i: Int) = lats[i]
        fun lon(i: Int) = lons[i]
    }
}

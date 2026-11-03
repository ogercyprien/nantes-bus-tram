package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.tools.RideShapeBuilder
import com.bustramnantes.transit.tools.VehicleGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** [RideShapes]: storage, carry-over, ride paths; [VehicleGraph] routing; the OSM profile. */
class RideShapesTest {

    private val fixture = File(javaClass.getResource("/gtfs-mini/routes.txt")!!.toURI()).parentFile
    private val net = GtfsConverter(GtfsSource.directory(fixture), feedId = "mini").convert()

    private fun roundTrip(network: TransitNetwork): TransitNetwork {
        val bytes = ByteArrayOutputStream().also { NetworkFormat.write(network, it) }.toByteArray()
        return NetworkFormat.read(ByteArrayInputStream(bytes))
    }

    /** A made-up interior point for every hop of [network]: the midpoint, nudged north. */
    private fun midpoints(network: TransitNetwork): RideShapes {
        val hops = HashMap<Long, DoubleArray>()
        for (p in network.patterns) for (k in 0 until p.quays.size - 1) {
            val a = p.quays[k]; val b = p.quays[k + 1]
            hops[RideShapes.key(network.quayNumber[a], network.quayNumber[b])] = doubleArrayOf(
                (network.quayLat[a] + network.quayLat[b]) / 2 + 0.001, (network.quayLon[a] + network.quayLon[b]) / 2,
            )
        }
        return RideShapes.of(hops)
    }

    @Test
    fun `ride shapes survive the packed format`() {
        val shapes = midpoints(net)
        val back = roundTrip(net.withRideShapes(shapes)).rideShapes!!
        assertEquals(shapes.keys.toList(), back.keys.toList())
        assertEquals(shapes.lat.toList(), back.lat.toList())
        assertEquals(shapes.lon.toList(), back.lon.toList())
        assertNull(roundTrip(net).rideShapes)
    }

    @Test
    fun `a version 2 file still reads, without ride shapes`() {
        val bytes = ByteArrayOutputStream().also { NetworkFormat.write(net, it) }.toByteArray()
        bytes[11] = 2 // big-endian version int after the 8-byte magic
        val v2 = NetworkFormat.read(ByteArrayInputStream(bytes))
        assertEquals(2, v2.formatVersion)
        assertNull(v2.rideShapes)
        assertEquals(net.tripCount, v2.tripCount)
    }

    @Test
    fun `ride path threads every quay and each hop's interior points`() {
        val withShapes = net.withRideShapes(midpoints(net))
        val p = withShapes.patterns.first { it.quays.size >= 3 }
        val path = withShapes.ridePath(p, 0, 2)
        // quay, mid, quay, mid, quay
        assertEquals(5, path.size)
        assertEquals(withShapes.quayLat[p.quays[0]], path[0][0], 0.0)
        assertEquals(withShapes.quayLat[p.quays[1]], path[2][0], 0.0)
        assertEquals(withShapes.quayLat[p.quays[2]], path[4][0], 0.0)
        assertTrue(path[1][0] > minOf(path[0][0], path[2][0]))
        // No shapes: straight from quay to quay.
        assertEquals(3, net.ridePath(p, 0, 2).size)
    }

    @Test
    fun `carry-over keeps the hops still used, by quay number, first source first`() {
        val previous = net.withRideShapes(midpoints(net))
        val carried = RideShapes.carriedOver(net, null, previous)!!
        assertEquals(net.hopKeys().size, carried.size)

        // A source that knows only one hop, listed first, wins for that hop.
        val oneKey = carried.keys[0]
        val other = net.withRideShapes(RideShapes.of(mapOf(oneKey to doubleArrayOf(1.0, 2.0, 3.0, 4.0))))
        val merged = RideShapes.carriedOver(net, other, previous)!!
        assertEquals(net.hopKeys().size, merged.size)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), merged.interior(merged.indexOf((oneKey ushr 32).toInt(), oneKey.toInt())).toList())

        // A hop the network no longer has is dropped.
        val stale = net.withRideShapes(RideShapes.of(mapOf(RideShapes.key(999_999, 999_998) to doubleArrayOf(1.0, 1.0))))
        assertNull(RideShapes.carriedOver(net, stale))
    }

    // ---------------------------------------------------------------- vehicle graph

    private fun way(id: Long, nodes: List<Pair<Long, Pair<Double, Double>>>, fwd: Boolean = true, bwd: Boolean = true) =
        VehicleGraph.Way(id, nodes.map { it.first }.toLongArray(), nodes.map { it.second.first }.toDoubleArray(), nodes.map { it.second.second }.toDoubleArray(), fwd, bwd)

    // A 2 × 2 block, ~110 m a side: corners SW(1) SE(2) NE(3) NW(4).
    private val sw = 1L to (47.2000 to -1.5500)
    private val se = 2L to (47.2000 to -1.5485)
    private val ne = 3L to (47.2010 to -1.5485)
    private val nw = 4L to (47.2010 to -1.5500)

    @Test
    fun `stops project onto the segment, not the nearest node, and paths start and end there`() {
        val g = VehicleGraph(listOf(way(10, listOf(sw, se))))
        val a = g.snap(47.20003, -1.5497, emptySet())!!
        val b = g.snap(47.20003, -1.5488, emptySet())!!
        assertEquals(47.2000, a.lat, 1e-6)
        val path = g.route(a, b, emptySet(), 1_000.0)!!
        assertEquals(2, path.size) // same segment: straight along it
    }

    @Test
    fun `one-way streets are only taken forwards`() {
        // South side one-way eastwards; the way back west must go round the block.
        val g = VehicleGraph(
            listOf(
                way(10, listOf(sw, se), bwd = false),
                way(11, listOf(se, ne)), way(12, listOf(ne, nw)), way(13, listOf(nw, sw)),
            ),
        )
        val east = g.snap(47.2000, -1.5488, emptySet())!!
        val west = g.snap(47.2000, -1.5497, emptySet())!!
        assertEquals(2, g.route(west, east, emptySet(), 10_000.0)!!.size)
        val back = g.route(east, west, emptySet(), 10_000.0)!!
        assertTrue("goes round the block: ${back.size} points", back.any { it[0] > 47.2005 })
        assertNull(g.route(east, west, emptySet(), 200.0)) // longer than allowed
    }

    @Test
    fun `the line's own relation ways are preferred over a slightly shorter path`() {
        // SW -> NE: via SE (way 10, 11) or via NW (12, 13) - same length; a bus whose relation lists
        // the NW side takes it.
        val g = VehicleGraph(listOf(way(10, listOf(sw, se)), way(11, listOf(se, ne)), way(12, listOf(sw, nw)), way(13, listOf(nw, ne))))
        val preferred = g.waysWithIds(setOf(12L, 13L))
        val a = g.snap(47.2000, -1.5500, preferred)!!
        val b = g.snap(47.2010, -1.5485, preferred)!!
        val path = g.route(a, b, preferred, 10_000.0)!!
        assertTrue(path.any { it[0] == 47.2010 && it[1] == -1.5500 })
    }

    @Test
    fun `bus profile`() {
        assertTrue(RideShapeBuilder.isBusWay(mapOf("highway" to "residential")))
        assertTrue(RideShapeBuilder.isBusWay(mapOf("highway" to "busway")))
        assertTrue(RideShapeBuilder.isBusWay(mapOf("highway" to "pedestrian", "bus" to "yes")))
        assertTrue(RideShapeBuilder.isBusWay(mapOf("highway" to "service", "access" to "no", "psv" to "designated")))
        assertTrue(!RideShapeBuilder.isBusWay(mapOf("highway" to "footway")))
        assertTrue(!RideShapeBuilder.isBusWay(mapOf("highway" to "residential", "access" to "private")))
        assertEquals(1, RideShapeBuilder.busOneway(mapOf("highway" to "primary", "oneway" to "yes")))
        assertEquals(0, RideShapeBuilder.busOneway(mapOf("highway" to "primary", "oneway" to "yes", "oneway:bus" to "no")))
        assertEquals(1, RideShapeBuilder.busOneway(mapOf("highway" to "primary", "junction" to "roundabout")))
        assertEquals(-1, RideShapeBuilder.busOneway(mapOf("highway" to "primary", "oneway" to "-1")))
        assertTrue(RideShapeBuilder.isNetworkRoute(mapOf("type" to "route", "route" to "bus", "network" to "Naolib")))
        assertTrue(RideShapeBuilder.isNetworkRoute(mapOf("type" to "route", "route" to "tram", "network" to "TAN")))
        assertTrue(!RideShapeBuilder.isNetworkRoute(mapOf("type" to "route", "route" to "bus", "network" to "Aléop")))
        // Collinear middle point dropped, a real corner kept.
        assertEquals(2, RideShapeBuilder.simplify(listOf(doubleArrayOf(47.0, -1.0), doubleArrayOf(47.0, -0.999), doubleArrayOf(47.0, -0.998)), 1.0).size)
        assertEquals(3, RideShapeBuilder.simplify(listOf(doubleArrayOf(47.0, -1.0), doubleArrayOf(47.001, -0.999), doubleArrayOf(47.0, -0.998)), 1.0).size)
    }
}

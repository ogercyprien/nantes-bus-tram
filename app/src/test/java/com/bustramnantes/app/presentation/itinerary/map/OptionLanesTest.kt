package com.bustramnantes.app.presentation.itinerary.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** [OptionLanes]: shared paths side by side, crossing-free order, ends meet; [optionRoutes] grouping. */
class OptionLanesTest {

    // Zoom 14: ~4.8 m per dp, so one lane ≈ 29 m.
    private val upd = OptionLanes.unitsPerDp(14.0)
    private val lane = OptionLanes.LANE_DP * upd

    private fun leg(vararg pts: Pair<Double, Double>, walk: Boolean = false) =
        OptionLanes.Leg(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), walk)

    private fun yAt(legs: List<OptionLanes.Leg>, x: Double): Double {
        val l = legs.single()
        val i = l.x.indices.minBy { abs(l.x[it] - x) }
        return l.y[i]
    }

    @Test
    fun `two options on the same street get a lane each, and meet at both ends`() {
        val street = leg(0.0 to 0.0, 3_000.0 to 0.0)
        val lanes = OptionLanes(listOf(listOf(street), listOf(street)), upd)
        val out = lanes.layout(lanes.order())
        val gap = abs(yAt(out[0], 1_500.0) - yAt(out[1], 1_500.0))
        assertEquals(lane, gap, lane * 0.1)
        // Symmetric about the street.
        assertEquals(0.0, yAt(out[0], 1_500.0) + yAt(out[1], 1_500.0), lane * 0.1)
        // Tapered to the street at the ends.
        assertEquals(0.0, out[0].single().y.first(), 1e-6)
        assertEquals(0.0, out[1].single().y.last(), 1e-6)
    }

    @Test
    fun `options that never share a path are not moved`() {
        val a = leg(0.0 to 0.0, 3_000.0 to 0.0)
        val b = leg(0.0 to 2_000.0, 3_000.0 to 2_000.0)
        val lanes = OptionLanes(listOf(listOf(a), listOf(b)), upd)
        val out = lanes.layout(lanes.order())
        assertEquals(0.0, yAt(out[0], 1_500.0), 1e-6)
        assertEquals(2_000.0, yAt(out[1], 1_500.0), 1e-6)
    }

    @Test
    fun `the order puts each option on the side it leaves towards, so they don't cross`() {
        // Both go east together, then option 0 turns north (left), option 1 south (right).
        val north = listOf(leg(0.0 to 0.0, 2_000.0 to 0.0, 2_000.0 to 2_000.0))
        val south = listOf(leg(0.0 to 0.0, 2_000.0 to 0.0, 2_000.0 to -2_000.0))
        for (options in listOf(listOf(north, south), listOf(south, north))) {
            val lanes = OptionLanes(options, upd)
            val rank = lanes.order()
            val out = lanes.layout(rank)
            val northIndex = if (options[0] === north) 0 else 1
            // Higher rank = further left (north, heading east).
            assertTrue(rank[northIndex] > rank[1 - northIndex])
            assertTrue(yAt(out[northIndex], 1_000.0) > yAt(out[1 - northIndex], 1_000.0))
        }
    }

    @Test
    fun `legs keep their kind and the layout ends where the path ends`() {
        val walk = leg(0.0 to 0.0, 200.0 to 0.0, walk = true)
        val ride = leg(200.0 to 0.0, 3_000.0 to 0.0)
        val lanes = OptionLanes(listOf(listOf(walk, ride), listOf(walk, ride)), upd)
        val out = lanes.layout(lanes.order())
        assertEquals(listOf(true, false), out[0].map { it.isWalk })
        assertEquals(3_000.0, out[0][1].x.last(), 1e-6)
    }

    @Test
    fun `a sharp turn leaves no loop on its inside lane`() {
        // East then straight back west 100 m north: a hairpin; both options share it.
        val hairpin = leg(0.0 to 0.0, 2_000.0 to 0.0, 2_000.0 to 100.0, 0.0 to 100.0)
        val lanes = OptionLanes(listOf(listOf(hairpin), listOf(hairpin)), upd)
        for (legs in lanes.layout(lanes.order())) {
            val l = legs.single()
            var crossings = 0
            for (i in 0 until l.x.size - 1) for (j in i + 2 until l.x.size - 1) {
                if (OptionLanes.segmentCross(l.x[i], l.y[i], l.x[i + 1], l.y[i + 1], l.x[j], l.y[j], l.x[j + 1], l.y[j + 1]) != null) crossings++
            }
            assertEquals(0, crossings)
        }
    }

    @Test
    fun `mercator round trip`() {
        val (x, y) = OptionLanes.toMercator(47.2184, -1.5536)
        val back = OptionLanes.fromMercator(x, y)
        assertEquals(47.2184, back.lat, 1e-9)
        assertEquals(-1.5536, back.lon, 1e-9)
        // ~0.6 m per dp at zoom 17 on 512-dp tiles (MapLibre's).
        assertEquals(0.597, OptionLanes.unitsPerDp(17.0), 0.001)
        assertTrue(hypot(x, y) > 0)
    }

    @Test
    fun `journeys on the same route share a colour and a line on the map`() {
        val path = listOf(47.20 to -1.55, 47.21 to -1.54)
        val c2 = JourneyFixtures.journey(JourneyFixtures.ride("C2", "E2001A", path, path))
        val c2Later = JourneyFixtures.journey(JourneyFixtures.ride("C2", "E2001A", path, path))
        val tram = JourneyFixtures.journey(JourneyFixtures.ride("3", "0076BD", path, path))
        val routes = optionRoutes(listOf(c2, tram, c2Later))
        assertEquals(listOf(listOf(0, 2), listOf(1)), routes.map { it.journeyIndices })
        assertEquals(OPTION_COLORS[0], routes[0].color)
        assertEquals(OPTION_COLORS[1], routes[1].color)
        assertEquals(mapOf(0 to 0, 2 to 0, 1 to 1), routeOfJourney(routes))
    }
}

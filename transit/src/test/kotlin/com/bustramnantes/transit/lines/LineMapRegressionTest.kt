package com.bustramnantes.transit.lines

import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.TransitNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Regressions of the line map on the real network (the bundled seed), one per case that shaped a
 * rule of [LineMapBuilder] (see `README.md` in the main source set's `lines` package). Builds the
 * four layouts once, about 10 s.
 */
class LineMapRegressionTest {

    companion object {
        private val net: TransitNetwork by lazy { File("../app/src/main/assets/seed/network.bin").inputStream().use { NetworkFormat.read(it) } }
        private val set: LineMapSet by lazy { LineMapSet.build(net) }

        /**
         * Crossings left after lane ordering, per layout zoom: about 5 % above what the builder
         * reached on 2026-11-05, after the step 7 fixes (254, 274, 330, 341). A rule change that tangles the map shows here.
         */
        private val CROSSING_BUDGET = mapOf(12 to 267, 13 to 288, 14 to 347, 15 to 358)
    }

    private fun stop(name: String) = net.stopPlaces.first { it.name == name }

    /** Ground metres between a marker and a quay. */
    private fun metres(m: LineMap.StopMarker, quay: Int) =
        hypot(WebMercator.x(net.quayLon[quay]) - m.x, WebMercator.y(net.quayLat[quay]) - m.y) / WebMercator.NANTES_SCALE

    @Test
    fun `crossings stay within budget at every layout zoom`() {
        for ((z, map) in set.layouts) {
            val c = map.crossings()
            assertTrue("z$z: $c crossings, budget ${CROSSING_BUDGET[z]}", c <= CROSSING_BUDGET.getValue(z))
        }
    }

    /** Manual adjustment: at Pirmil the trams keep the west side of every bundle round the station. */
    @Test
    fun `pirmil trams keep the west side`() {
        val pirmil = stop("Pirmil").id
        for ((z, map) in set.layouts) {
            val hub = map.stops.single { it.stopId == pirmil && it.hub }
            val node = map.nodeX.indices.single { hypot(map.nodeX[it] - hub.x, map.nodeY[it] - hub.y) < 0.5 }
            var checked = 0
            for (s in map.segments) {
                if (s.first != node && s.last != node) continue
                val trams = s.order.indices.filter { map.style.kind[s.order[it]] == LineStyle.Kind.TRAM }
                if (trams.isEmpty() || trams.size == s.order.size) continue
                val northwards = s.y.last() > s.y.first()
                val expected = if (northwards) s.order.indices.take(trams.size) else s.order.indices.toList().takeLast(trams.size)
                assertEquals("z$z: trams on the west side of a bundle at Pirmil", expected, trams)
                checked++
            }
            assertTrue("z$z: a mixed bundle at Pirmil", checked > 0)
        }
    }

    /** Place Mellinet's ring is one junction, not a knot of lanes swapping round it. */
    @Test
    fun `mellinet roundabout is one junction`() {
        val mel = stop("Mellinet")
        val cx = mel.quays.map { WebMercator.x(net.quayLon[it]) }.average()
        val cy = mel.quays.map { WebMercator.y(net.quayLat[it]) }.average()
        for (z in 13..15) {
            val map = set.layouts.getValue(z)
            val near = map.nodeX.indices.count { hypot(map.nodeX[it] - cx, map.nodeY[it] - cy) < 100 * WebMercator.NANTES_SCALE }
            assertTrue("z$z: $near junctions round Mellinet", near <= 1)
        }
    }

    /** Line 30's runs to Îles de Loire are minor variants, drawn because they serve stops of their own. */
    @Test
    fun `line 30 is drawn to iles de loire`() {
        val stopId = stop("Îles de Loire").id
        for ((z, map) in set.layouts) {
            val r30 = map.routes.indexOfFirst { it.shortName == "30" }
            assertTrue("z$z: line 30 drawn", map.segments.any { r30 in it.order })
            assertTrue("z$z: Îles de Loire has a marker", map.stops.any { it.stopId == stopId })
        }
    }

    /** Mont Blanc's quays are 110 m apart along one road: its dot sits between them, not at one end. */
    @Test
    fun `mont blanc dot sits between its staggered quays`() {
        val mb = stop("Mont Blanc")
        for (z in 13..15) {
            val markers = set.layouts.getValue(z).stops.filter { it.stopId == mb.id }
            assertEquals("z$z: one marker", 1, markers.size)
            for (q in mb.quays) assertTrue("z$z: ${metres(markers[0], q)} m from a quay", metres(markers[0], q) < 100)
        }
    }

    /** Toutes Joies: 26 and C8 stop 220 m apart on two streets, one marker each from z13. */
    @Test
    fun `toutes joies gets a marker per direction`() {
        val id = stop("Toutes Joies").id
        assertEquals(1, set.layouts.getValue(12).stops.count { it.stopId == id })
        for (z in 13..15) assertEquals("z$z", 2, set.layouts.getValue(z).stops.count { it.stopId == id })
    }

    /** Place du Cirque was pulled 250 m onto St-Nicolas at z13: it stays by its quays. */
    @Test
    fun `place du cirque stays by its quays`() {
        val pc = stop("Place du Cirque")
        for (z in 13..15) {
            val m = set.layouts.getValue(z).stops.single { it.stopId == pc.id }
            for (q in pc.quays) assertTrue("z$z: ${metres(m, q)} m from a quay", metres(m, q) < 150)
        }
    }

    /** Two stations never merge: neighbouring hubs made one star of the centre at z12. */
    @Test
    fun `station hubs never merge`() {
        for ((z, map) in set.layouts) {
            val hubs = map.stops.filter { it.hub }
            assertTrue("z$z: ${hubs.size} hubs", hubs.size >= 20)
            for (i in hubs.indices) for (j in i + 1 until hubs.size) {
                assertTrue("z$z: ${hubs[i].stopId} and ${hubs[j].stopId} share a junction", hypot(hubs[i].x - hubs[j].x, hubs[i].y - hubs[j].y) > 1.0)
            }
        }
    }

    /** Haluchère - Batignolles: no loop tighter than its bundle (the knot of the bus station loops). */
    @Test
    fun `haluchere has no knot`() {
        val h = stop("Haluchère - Batignolles")
        val cx = WebMercator.x(h.lon); val cy = WebMercator.y(h.lat)
        for ((z, map) in set.layouts) for (s in map.segments) {
            if (s.first != s.last || s.x.size < 3) continue
            if (s.x.indices.none { hypot(s.x[it] - cx, s.y[it] - cy) < 300 * WebMercator.NANTES_SCALE }) continue
            val len = (1 until s.x.size).sumOf { hypot(s.x[it] - s.x[it - 1], s.y[it] - s.y[it - 1]) }
            val bundle = map.style.bundleWidth(s.order, z.toDouble()) * WebMercator.unitsPerDp(z.toDouble())
            assertTrue("z$z: a loop of ${len.toInt()} m for a bundle of ${bundle.toInt()} m", len >= Math.PI * bundle)
        }
    }

    /** The lane geometry is finite and draws every line the map carries. */
    @Test
    fun `lane geometry covers every drawn line`() {
        for (zoom in listOf(12.0, 14.5, 17.0)) {
            val map = set.layoutFor(zoom)
            val geometry = LaneGeometry.compute(map, zoom)
            val drawn = map.segments.flatMap { s -> s.order.asIterable() }.filter { map.style.kind[it] != LineStyle.Kind.HIDDEN }.toSet()
            assertEquals(drawn, geometry.pieces.map { it.route }.toSet())
            assertTrue(geometry.pieces.all { p -> p.x.all { it.isFinite() } && p.y.all { it.isFinite() } && p.x.size >= 2 })
            assertEquals(zoom >= LineMap.HUB_DASH_MIN_ZOOM, geometry.dashes.isNotEmpty())
        }
    }
}

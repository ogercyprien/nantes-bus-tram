package com.bustramnantes.transit.lines

import com.bustramnantes.transit.NetworkFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/** [LineMapFormat] round trip, and the build is deterministic (the seed is rebuilt byte for byte). */
class LineMapFormatTest {
    private val net = File("../app/src/main/assets/seed/network.bin").inputStream().use { NetworkFormat.read(it) }

    private fun bytes(set: LineMapSet) = ByteArrayOutputStream().also { LineMapFormat.write(set, it) }.toByteArray()

    @Test
    fun `round trip keeps the layout to the decimetre`() {
        val set = LineMapSet.build(net, 12..12)
        val read = LineMapFormat.read(bytes(set).inputStream())
        assertEquals(set.feedId, read.feedId)
        val a = set.layouts.getValue(12); val b = read.layouts.getValue(12)
        assertEquals(a.routes.map { it.id to it.kind }, b.routes.map { it.id to it.kind })
        assertEquals(a.segments.size, b.segments.size)
        for (i in a.segments.indices) {
            assertArrayEquals(a.segments[i].order, b.segments[i].order)
            assertEquals(a.segments[i].first, b.segments[i].first)
            assertTrue(a.segments[i].x.indices.all { abs(a.segments[i].x[it] - b.segments[i].x[it]) <= 0.05 })
        }
        assertEquals(a.transitions.size, b.transitions.size)
        assertEquals(a.stops.map { it.stopId to it.hub }, b.stops.map { it.stopId to it.hub })
        assertEquals(a.stops.map { it.dashSegment }, b.stops.map { it.dashSegment })
        // The orders were optimised on the exact geometry; read back, the same crossings remain.
        assertEquals(a.crossings(), b.crossings())
    }

    @Test
    fun `building twice gives the same bytes`() {
        assertArrayEquals(bytes(LineMapSet.build(net, 13..13)), bytes(LineMapSet.build(net, 13..13)))
    }
}

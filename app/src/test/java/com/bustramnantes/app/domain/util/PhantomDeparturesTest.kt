package com.bustramnantes.app.domain.util

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomDeparturesTest {

    private val now = Instant.parse("2026-10-05T16:00:00Z")

    private fun at(minutes: Int) = Instant.fromEpochSeconds(now.epochSeconds + minutes * 60L)

    private fun dep(minutes: Int, rt: Boolean, line: String = "2", dir: String = "1") = Departure(
        lineId = line, lineNumber = line, lineName = line, lineColor = "#000000", lineTextColor = "#FFFFFF",
        transportMode = TransportMode.TRAM, direction = Direction(dir, "Dir $dir"),
        nextDepartedTime = at(minutes), nextArrivalTime = at(minutes), isRealtime = rt,
    )

    private fun List<Departure>.tagged() = tagSuspectedPhantoms(now).map { it.isSuspectedPhantom }

    @Test
    fun `an imminent theoretical departure on a line with real-time data is tagged`() {
        assertEquals(listOf(true, false), listOf(dep(5, rt = false), dep(11, rt = true)).tagged())
    }

    @Test
    fun `the imminent window ends at 8 minutes`() {
        val board = listOf(dep(8, rt = false), dep(9, rt = false), dep(40, rt = true, dir = "0"))
        assertEquals(listOf(true, false, false), board.tagged())
    }

    @Test
    fun `a theoretical departure sandwiched between real-time ones is tagged however far away`() {
        val board = listOf(dep(20, rt = true), dep(30, rt = false), dep(40, rt = true), dep(50, rt = false))
        // 30 is between two tracked trams; 50 has no tracked tram after it - just beyond the horizon.
        assertEquals(listOf(false, true, false, false), board.tagged())
    }

    @Test
    fun `the sandwich only counts real-time departures of the same direction`() {
        val board = listOf(dep(20, rt = true, dir = "0"), dep(30, rt = false), dep(40, rt = true, dir = "0"))
        assertEquals(listOf(false, false, false), board.tagged())
    }

    @Test
    fun `lines without any real-time data are never tagged, even a minute away`() {
        // TE2 / N3 / 1B / suburban 131… only ever publish theoretical times.
        val board = listOf(dep(1, rt = false, line = "TE2"), dep(3, rt = true, line = "C2"))
        assertEquals(listOf(false, false), board.tagged())
    }

    @Test
    fun `real-time departures are never tagged and order is preserved`() {
        val board = listOf(dep(1, rt = true), dep(2, rt = false), dep(3, rt = true))
        val result = board.tagSuspectedPhantoms(now)
        assertEquals(board.map { it.nextDepartedTime }, result.map { it.nextDepartedTime })
        assertFalse(result[0].isSuspectedPhantom)
        assertTrue(result[1].isSuspectedPhantom)
    }
}

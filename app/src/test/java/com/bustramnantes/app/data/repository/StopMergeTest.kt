package com.bustramnantes.app.data.repository

import com.bustramnantes.app.domain.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [mergeDuplicateStops]: same-name adjacent stops collapse; same-name distant stops don't. */
class StopMergeTest {

    private fun stop(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        lines: List<String>,
    ) = Stop(id = id, name = name, lat = lat, lon = lon, linkedLines = lines, accessible = false)

    @Test
    fun `two adjacent same-name stops merge into the one serving more lines, union of lines`() {
        // ~78 m apart (0.0007 deg latitude), like the two "Gare Sud" platforms.
        val main = stop("65358", "Gare Sud", 47.2000, -1.5000, listOf("5", "C2", "C3", "C8", "NA", "NS"))
        val small = stop("58773", "Gare Sud", 47.20070, -1.5000, listOf("NA"))

        val merged = mergeDuplicateStops(listOf(small, main))

        assertEquals(1, merged.size)
        assertEquals("65358", merged[0].id) // representative = most lines, regardless of input order
        assertEquals(listOf("5", "C2", "C3", "C8", "NA", "NS"), merged[0].linkedLines)
        // Both StopPlaces stay reachable through the merged stop (departures use all their quays).
        assertEquals(setOf("65358", "58773"), merged[0].memberIds.toSet())
    }

    @Test
    fun `same name but far apart stays separate`() {
        // The real "Jean Jaurès" pair is ~9 km apart.
        val a = stop("60801", "Jean Jaurès", 47.2500, -1.5500, listOf("3"))
        val b = stop("65402", "Jean Jaurès", 47.2000, -1.6500, listOf("96", "LCE", "LCN"))

        val merged = mergeDuplicateStops(listOf(a, b))

        assertEquals(2, merged.size)
    }

    @Test
    fun `accent and case differences in name are treated as the same stop`() {
        val a = stop("1", "Hôtel Dieu", 47.2100, -1.5500, listOf("2"))
        val b = stop("2", "hotel dieu", 47.21050, -1.5500, listOf("3"))

        val merged = mergeDuplicateStops(listOf(a, b))

        assertEquals(1, merged.size)
        assertTrue(merged[0].linkedLines.containsAll(listOf("2", "3")))
    }

    @Test
    fun `unique stops pass through untouched`() {
        val stops = listOf(
            stop("1", "Commerce", 47.21, -1.55, listOf("1", "2")),
            stop("2", "Bretagne", 47.22, -1.56, listOf("C1")),
        )
        assertEquals(2, mergeDuplicateStops(stops).size)
    }
}

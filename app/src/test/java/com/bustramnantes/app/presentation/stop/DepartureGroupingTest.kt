package com.bustramnantes.app.presentation.stop

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.ServedLine
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [buildDepartureGroups] - the merge that keeps every line/direction of a
 * stop visible at all times, even after the day's last departure. Models the
 * real-world scenario reported by the user: at Rond-Point de Rennes late in the evening only C2 was
 * still running, and every other line had vanished instead of showing "fin de service".
 */
class DepartureGroupingTest {

    private fun departure(lineId: String, number: String, dirCode: String, dirName: String, epochSec: Long) =
        Departure(
            lineId = lineId,
            lineNumber = number,
            lineName = number,
            lineColor = "#000000",
            lineTextColor = "#FFFFFF",
            transportMode = TransportMode.BUS,
            direction = Direction(dirCode, dirName),
            nextDepartedTime = Instant.fromEpochSeconds(epochSec),
            nextArrivalTime = Instant.fromEpochSeconds(epochSec),
            isRealtime = false,
        )

    private fun servedLine(lineId: String, number: String, vararg directions: Pair<String, String>) =
        ServedLine(
            lineId = lineId,
            lineNumber = number,
            lineName = number,
            lineColor = "#000000",
            lineTextColor = "#FFFFFF",
            transportMode = TransportMode.BUS,
            directions = directions.map { Direction(it.first, it.second) },
        )

    /** The reported scenario: only C2 still runs; all other served lines must still appear, idle. */
    @Test
    fun `served lines with no departures appear as end-of-service`() {
        val served = listOf(
            servedLine("11550", "C2", "0" to "Le Cardo", "1" to "Gare Sud"),
            servedLine("11469", "10", "0" to "Gare de Chantenay", "1" to "Bd de Doulon"),
            servedLine("11484", "12", "0" to "Jules Verne", "1" to "Beauséjour"),
        )
        // Only C2/Le Cardo has an upcoming bus.
        val departures = listOf(departure("11550", "C2", "0", "Le Cardo", 1_000))

        val groups = buildDepartureGroups(served, departures)

        // One group per served line+direction: 3 lines x 2 directions = 6 rows, none dropped.
        assertEquals(6, groups.size)

        val c2LeCardo = groups.first { it.lineNumber == "C2" && it.direction.code == "0" }
        assertFalse("C2/Le Cardo has a live departure", c2LeCardo.isEndOfService)
        assertEquals(1, c2LeCardo.departures.size)

        // Every other line/direction is present but marked end-of-service.
        val idle = groups.filterNot { it.lineNumber == "C2" && it.direction.code == "0" }
        assertEquals(5, idle.size)
        assertTrue("all other rows are end-of-service", idle.all { it.isEndOfService })
        assertTrue("C2/Gare Sud is shown even with no bus due", groups.any { it.lineNumber == "C2" && it.direction.name == "Gare Sud" && it.isEndOfService })
    }

    /** A terminus line legitimately serves a single direction - no phantom second row. */
    @Test
    fun `terminus line yields a single direction group`() {
        val served = listOf(servedLine("99", "NO", "0" to "Grootaers"))
        val groups = buildDepartureGroups(served, emptyList())
        assertEquals(1, groups.size)
        assertEquals("Grootaers", groups.single().direction.name)
        assertTrue(groups.single().isEndOfService)
    }

    /** A live departure whose direction isn't in the served set must never be dropped. */
    @Test
    fun `live departure missing from served lines is still shown`() {
        val served = listOf(servedLine("11550", "C2", "0" to "Le Cardo"))
        val departures = listOf(
            departure("11550", "C2", "0", "Le Cardo", 500),
            // A direction the served lines don't list (newer feed than the installed network) - still a real bus.
            departure("11550", "C2", "1", "Gare Sud", 700),
        )
        val groups = buildDepartureGroups(served, departures)
        assertEquals(2, groups.size)
        assertTrue(groups.all { !it.isEndOfService })
    }

    /** With no served-line data at all, behaviour degrades to plain live grouping. */
    @Test
    fun `empty served lines falls back to live departures only`() {
        val departures = listOf(
            departure("11550", "C2", "0", "Le Cardo", 500),
            departure("11469", "10", "1", "Bd de Doulon", 600),
        )
        val groups = buildDepartureGroups(emptyList(), departures)
        assertEquals(2, groups.size)
        assertTrue("no idle rows appear without served-line data", groups.none { it.isEndOfService })
    }

    /** Numeric-aware ordering: 2 < 10 < C2, then by direction name. */
    @Test
    fun `groups are sorted numerically then by direction`() {
        val served = listOf(
            servedLine("c2", "C2", "0" to "Le Cardo"),
            servedLine("l10", "10", "0" to "Nord"),
            servedLine("l2", "2", "0" to "Sud"),
        )
        val groups = buildDepartureGroups(served, emptyList())
        assertEquals(listOf("2", "10", "C2"), groups.map { it.lineNumber })
    }

    @Test
    fun `short runs are labelled, dual-destination branches are not`() {
        fun dep(dest: String?) = departure("2", "2", "0", "Gare de Pont Rousseau", 1_000).copy(destinationName = dest)
        assertEquals("Hôtel Dieu", dep("Hôtel Dieu").shortRunDestination("Gare de Pont Rousseau"))
        assertEquals(null, dep("Gare de Pont Rousseau").shortRunDestination("Gare de Pont Rousseau"))
        assertEquals(null, dep("Babinière").shortRunDestination("Beaujoire / Babinière"))
        assertEquals(null, dep(null).shortRunDestination("Beaujoire / Babinière"))
    }
}

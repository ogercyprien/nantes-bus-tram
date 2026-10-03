package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [trimAccessEgressWalks] - dropping the redundant walk to/from a picked stop while
 * keeping legitimate walks (to a different stop, or in walk-only journeys).
 */
class JourneyWalkTrimmingTest {

    private fun t(sec: Long) = Instant.fromEpochSeconds(sec)

    private fun walk(distance: Int = 200, duration: Int = 180) = JourneySection.Walking(
        pathLinks = emptyList(),
        magneticDirection = 0.0,
        durationSeconds = duration,
        distanceMeters = distance,
    )

    private fun transit(
        line: String,
        boardStopId: String,
        alightStopId: String,
        depSec: Long,
        arrSec: Long,
    ) = JourneySection.Transit(
        lineId = line,
        lineNumber = line,
        lineColor = "#000000",
        lineTextColor = "#FFFFFF",
        transportMode = TransportMode.BUS,
        directionName = "Somewhere",
        concernedStops = listOf(
            ConcernedStop(boardStopId, "board", 0.0, 0.0, t(depSec)),
            ConcernedStop(alightStopId, "alight", 0.0, 0.0, t(arrSec)),
        ),
        tripGeometryWkt = "",
        timeSourceType = TimeSourceType.THEORICAL,
        departureTime = t(depSec),
        arrivalTime = t(arrSec),
    )

    private fun journey(sections: List<JourneySection>, depSec: Long, arrSec: Long) = Journey(
        sections = sections,
        totalDurationSeconds = (arrSec - depSec).toInt(),
        departureTime = t(depSec),
        arrivalTime = t(arrSec),
        transferCount = sections.count { it is JourneySection.Transit } - 1,
    )

    /** From my picked stop to my picked stop: both access and egress walks dropped, times re-anchored. */
    @Test
    fun `drops leading and trailing walk at picked stops and re-anchors times`() {
        val bus = transit("26", boardStopId = "57116", alightStopId = "37178", depSec = 300, arrSec = 900)
        val j = journey(listOf(walk(), bus, walk()), depSec = 0, arrSec = 1000)

        val trimmed = j.trimAccessEgressWalks(fromStopId = "57116", toStopId = "37178")

        assertEquals(listOf<JourneySection>(bus), trimmed.sections)
        assertEquals(t(300), trimmed.departureTime)
        assertEquals(t(900), trimmed.arrivalTime)
        assertEquals(600, trimmed.totalDurationSeconds)
    }

    /** The user's kept case: the leading walk goes to a DIFFERENT stop, so it must stay. */
    @Test
    fun `keeps leading walk when it leads to a different stop`() {
        val bus = transit("26", boardStopId = "99999", alightStopId = "37178", depSec = 300, arrSec = 900)
        val j = journey(listOf(walk(), bus), depSec = 0, arrSec = 900)

        val trimmed = j.trimAccessEgressWalks(fromStopId = "57116", toStopId = null)

        assertSame("nothing to trim -> same instance", j, trimmed)
    }

    /** A middle transfer walk between two rides is never touched. */
    @Test
    fun `keeps transfer walk between rides`() {
        val bus1 = transit("26", boardStopId = "57116", alightStopId = "60759", depSec = 300, arrSec = 600)
        val bus2 = transit("3", boardStopId = "60759", alightStopId = "37178", depSec = 700, arrSec = 900)
        val j = journey(listOf(walk(), bus1, walk(), bus2, walk()), depSec = 0, arrSec = 1000)

        val trimmed = j.trimAccessEgressWalks(fromStopId = "57116", toStopId = "37178")

        assertEquals(listOf(bus1, walk(), bus2).map { it::class }, trimmed.sections.map { it::class })
        assertTrue("middle walk kept", trimmed.sections[1] is JourneySection.Walking)
        assertEquals(t(300), trimmed.departureTime)
        assertEquals(t(900), trimmed.arrivalTime)
    }

    /** Walk-only journey (no transit) is left completely alone even if endpoints are stops. */
    @Test
    fun `walk-only journey is untouched`() {
        val j = journey(listOf(walk(distance = 400)), depSec = 0, arrSec = 400)
        val trimmed = j.trimAccessEgressWalks(fromStopId = "57116", toStopId = "37178")
        assertSame(j, trimmed)
    }

    /** Only trailing trim requested: leading walk stays, arrival re-anchored to the ride. */
    @Test
    fun `trims only trailing walk when only destination is a stop`() {
        val bus = transit("26", boardStopId = "99999", alightStopId = "37178", depSec = 300, arrSec = 900)
        val j = journey(listOf(walk(), bus, walk()), depSec = 0, arrSec = 1000)

        val trimmed = j.trimAccessEgressWalks(fromStopId = null, toStopId = "37178")

        assertEquals(2, trimmed.sections.size)
        assertTrue("leading walk kept", trimmed.sections.first() is JourneySection.Walking)
        assertEquals("departure unchanged (walk kept)", t(0), trimmed.departureTime)
        assertEquals("arrival re-anchored to ride", t(900), trimmed.arrivalTime)
    }
}

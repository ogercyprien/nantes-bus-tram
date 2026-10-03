package com.bustramnantes.app.presentation.itinerary

import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.usecase.trimAccessEgressWalks
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [totalWalkingMinutes] / [formatTotalWalkingTime] - the "Temps total de marche" line of
 * the itinerary summary card.
 */
class TotalWalkingTimeTest {

    private fun t(sec: Long) = Instant.fromEpochSeconds(sec)

    private fun walk(duration: Int) = JourneySection.Walking(
        pathLinks = emptyList(),
        magneticDirection = 0.0,
        durationSeconds = duration,
        distanceMeters = 100,
    )

    private fun transit(boardStopId: String = "A", alightStopId: String = "B") = JourneySection.Transit(
        lineId = "C2",
        lineNumber = "C2",
        lineColor = "#000000",
        lineTextColor = "#FFFFFF",
        transportMode = TransportMode.BUS,
        directionName = "Somewhere",
        concernedStops = listOf(
            ConcernedStop(boardStopId, "board", 0.0, 0.0, t(300)),
            ConcernedStop(alightStopId, "alight", 0.0, 0.0, t(900)),
        ),
        tripGeometryWkt = "",
        timeSourceType = TimeSourceType.THEORICAL,
        departureTime = t(300),
        arrivalTime = t(900),
    )

    @Test
    fun `sums walk legs and ignores transit legs`() {
        val sections = listOf(walk(240), transit(), walk(480))
        assertEquals(12L, totalWalkingMinutes(sections))
        assertEquals("Temps total de marche : 12 min", formatTotalWalkingTime(sections))
    }

    /** Each leg is rounded on its own, like the detail view's "~N min", so the totals agree. */
    @Test
    fun `rounds each leg individually to match the detail view`() {
        // 90 s -> "~2 min" per leg in the detail view; total must read 4, not round(180 s) = 3.
        assertEquals(4L, totalWalkingMinutes(listOf(walk(90), transit(), walk(90))))
        // 20 s -> "~0 min" each.
        assertEquals(0L, totalWalkingMinutes(listOf(walk(20), transit(), walk(20))))
    }

    @Test
    fun `formats an hour or more as h mm`() {
        assertEquals("Temps total de marche : 1 h 05", formatTotalWalkingTime(listOf(walk(65 * 60))))
    }

    @Test
    fun `no walking yields no line`() {
        assertNull(formatTotalWalkingTime(listOf(transit())))
        assertNull(formatTotalWalkingTime(listOf(walk(20), transit())))
        assertNull(formatTotalWalkingTime(emptyList()))
    }

    /** Access/egress walks trimmed at picked stops must not count towards the displayed total. */
    @Test
    fun `reflects trimmed access and egress walks`() {
        val journey = Journey(
            sections = listOf(walk(300), transit(boardStopId = "57116", alightStopId = "37178"), walk(600)),
            totalDurationSeconds = 1800,
            departureTime = t(0),
            arrivalTime = t(1800),
            transferCount = 0,
        )
        assertEquals(15L, totalWalkingMinutes(journey.sections))

        val fromStop = journey.trimAccessEgressWalks(fromStopId = "57116", toStopId = null)
        assertEquals(10L, totalWalkingMinutes(fromStop.sections))

        val stopToStop = journey.trimAccessEgressWalks(fromStopId = "57116", toStopId = "37178")
        assertNull(formatTotalWalkingTime(stopToStop.sections))
    }
}

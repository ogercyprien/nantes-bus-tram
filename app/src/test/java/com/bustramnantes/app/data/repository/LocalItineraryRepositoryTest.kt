package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.presentation.common.utils.parseWktLineString
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import java.io.File
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [planJourneys] on the `:transit` mini GTFS fixture (Tuesday 2026-10-13). */
class LocalItineraryRepositoryTest {

    private val index = NetworkIndex(GtfsConverter(GtfsSource.directory(File("../transit/src/test/resources/gtfs-mini"))).convert())
    private val paris = TimeZone.of("Europe/Paris")
    private fun hm(i: Instant) = i.toLocalDateTime(paris).time.toString()
    private val alpha = "FR_NAOLIB:StopPlace:1"
    private val gamma = "FR_NAOLIB:StopPlace:3"
    private val delta = "FR_NAOLIB:StopPlace:4"
    private fun departAt(h: Int, m: Int) = ItineraryTimePreference.DepartAt(LocalDateTime(2026, 10, 13, h, m))

    @Test
    fun `stop to stop direct ride, no walk legs, GTFS identity on the leg`() {
        val js = planJourneys(index, 47.2, -1.56, 47.2, -1.54, departAt(7, 55), fromStopId = alpha, toStopId = gamma)
        val j = js.first()
        assertEquals("08:00", hm(j.departureTime))
        assertEquals("08:10", hm(j.arrivalTime))
        val ride = j.sections.single() as JourneySection.Transit
        assertEquals("FR_NAOLIB:Line:T1", ride.lineId)
        assertEquals("T1", ride.lineNumber)
        assertEquals("007A45", ride.lineColor)
        assertEquals("Gamma", ride.directionName)
        assertEquals(1, ride.directionId)
        assertEquals(TimeSourceType.THEORICAL, ride.timeSourceType)
        assertEquals(listOf(alpha, "FR_NAOLIB:StopPlace:2", gamma), ride.concernedStops.map { it.stopId })
        assertEquals(alpha, ride.boardingStopId)
        assertTrue(ride.tripId!!.endsWith("@2026-10-13"))
        // Shape slice between the two quays (the fixture's T1A shape has 3 points).
        assertEquals(3, parseWktLineString(ride.tripGeometryWkt).size)
    }

    @Test
    fun `transfer with a walk between quays, from and to addresses`() {
        // From a point next to Alpha to a point next to Delta.
        val j = planJourneys(index, 47.2001, -1.5602, 47.2101, -1.5301, departAt(7, 50)).first { it.sections.any { s -> s is JourneySection.Transit } }
        val kinds = j.sections.map { if (it is JourneySection.Transit) it.lineNumber else "walk" }
        assertEquals(listOf("walk", "T1", "walk", "B3", "walk"), kinds)
        assertEquals("08:28", hm((j.sections[3] as JourneySection.Transit).arrivalTime))
        assertEquals(1, j.transferCount)
        val transfer = j.sections[2] as JourneySection.Walking
        assertTrue(transfer.distanceMeters in 200..350)
        assertTrue("bearing north-ish: ${transfer.magneticDirection}", transfer.magneticDirection < 20 || transfer.magneticDirection > 340)
    }

    @Test
    fun `arrive by returns the latest departures first`() {
        val js = planJourneys(index, 0.0, 0.0, 0.0, 0.0, ItineraryTimePreference.ArriveBy(LocalDateTime(2026, 10, 13, 8, 45)), fromStopId = alpha, toStopId = gamma)
        assertEquals(listOf("08:30", "08:00"), js.filter { it.sections.any { s -> s is JourneySection.Transit } }.map { hm(it.departureTime) })
    }

    @Test
    fun `excluded run is never proposed`() {
        val first = planJourneys(index, 0.0, 0.0, 0.0, 0.0, departAt(7, 55), fromStopId = alpha, toStopId = gamma).first()
        val trip = (first.sections.single() as JourneySection.Transit).tripId!!
        val again = planJourneys(index, 0.0, 0.0, 0.0, 0.0, departAt(7, 55), fromStopId = alpha, toStopId = gamma, excludedTripIds = setOf(trip))
        assertEquals("08:30", hm(again.first().departureTime))
    }

    @Test
    fun `short distances also get a walk-only option, dates outside the feed get nothing else`() {
        val js = planJourneys(index, 47.2, -1.56, 47.2, -1.55, departAt(7, 55))
        assertTrue(js.any { j -> j.sections.all { it is JourneySection.Walking } })
        val outside = planJourneys(index, 47.2, -1.56, 47.2, -1.54, ItineraryTimePreference.DepartAt(LocalDateTime(2027, 3, 1, 8, 0)))
        assertTrue(outside.all { j -> j.sections.none { it is JourneySection.Transit } })
    }

    @Test
    fun `now uses the given clock`() {
        val now = LocalDateTime(2026, 10, 13, 8, 20).toInstant(paris)
        val j = planJourneys(index, 0.0, 0.0, 0.0, 0.0, ItineraryTimePreference.Now, fromStopId = alpha, toStopId = gamma, now = now).first()
        assertEquals("08:30", hm(j.departureTime))
    }
}

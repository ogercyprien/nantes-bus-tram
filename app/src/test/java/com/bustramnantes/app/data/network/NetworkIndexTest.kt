package com.bustramnantes.app.data.network

import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [NetworkIndex] over the `:transit` mini GTFS fixture: stops, lines, served lines, timetable departures. */
class NetworkIndexTest {

    private val raw = GtfsConverter(GtfsSource.directory(File("../transit/src/test/resources/gtfs-mini"))).convert()
    private val index = NetworkIndex(raw)

    @Test
    fun `lines carry GTFS ids, colours without hash and both directions`() {
        val t1 = index.line("T1")!!
        assertEquals("FR_NAOLIB:Line:T1", t1.id)
        assertEquals("007A45", t1.color)
        assertEquals(TransportMode.TRAM, t1.mode)
        assertEquals(listOf("0" to "Gamma", "1" to "Alpha"), t1.directions.map { it.code to it.name })
    }

    @Test
    fun `stops resolve by StopPlace id`() {
        val alpha = index.stop("FR_NAOLIB:StopPlace:1")!!
        assertEquals("Alpha", alpha.name)
        assertEquals(listOf("T1"), alpha.linkedLines)
        assertNull(index.stop("nope"))
    }

    @Test
    fun `served lines are terminus-aware`() {
        val gamma = index.servedLines("FR_NAOLIB:StopPlace:3")
        assertEquals(listOf("T1"), gamma.map { it.lineNumber })
        assertEquals(listOf("1" to "Alpha"), gamma.single().directions.map { it.code to it.name })
        assertEquals("#007A45", gamma.single().lineColor)
        assertTrue(index.servedLines("FR_NAOLIB:StopPlace:4").isEmpty())
    }

    @Test
    fun `scheduled departures are theoretical, in GTFS identity, terminating trips excluded`() {
        val now = LocalDateTime(2026, 10, 13, 7, 55).toInstant(TimeZone.of("Europe/Paris"))
        val beta = index.scheduledDepartures("FR_NAOLIB:StopPlace:2", now).filter { it.nextDepartedTime <= now + 1.hours }
        assertEquals(listOf("T1" to "0", "B2" to "0", "T1" to "1", "T1" to "0"), beta.map { it.lineNumber to it.direction.code })
        assertTrue(beta.none { it.isRealtime })
        assertEquals("FR_NAOLIB:Line:T1", beta.first().lineId)
        // Gamma: T1 dir 0 terminates there - only the outbound 08:30 run is listed.
        val gamma = index.scheduledDepartures("FR_NAOLIB:StopPlace:3", now).filter { it.nextDepartedTime <= now + 1.hours }
        assertEquals(listOf("1"), gamma.map { it.direction.code })
    }
}

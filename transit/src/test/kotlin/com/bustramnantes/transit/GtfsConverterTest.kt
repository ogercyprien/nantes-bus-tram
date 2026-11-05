package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.CsvReader
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Convert -> pack -> read the hand-written `gtfs-mini` feed and query it. */
class GtfsConverterTest {

    private val fixture = File(javaClass.getResource("/gtfs-mini/routes.txt")!!.toURI()).parentFile

    private fun convert(): TransitNetwork {
        val network = GtfsConverter(GtfsSource.directory(fixture), feedId = "mini").convert()
        // Always go through the packed form, so every assertion also covers the round trip.
        val bytes = ByteArrayOutputStream().also { NetworkFormat.write(network, it) }.toByteArray()
        return NetworkFormat.read(ByteArrayInputStream(bytes))
    }

    private val net by lazy { convert() }
    private fun sp(number: Int) = net.stopPlaceIndexByNumber(number)!!
    private fun route(name: String) = net.routeIndex(name)!!

    @Test
    fun `csv reader handles BOM, CRLF, quotes and embedded commas`() {
        val text = "﻿a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",3\r\n\nlast,,\n"
        val reader = CsvReader.of(ByteArrayInputStream(text.toByteArray()))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), reader.header)
        val rows = ArrayList<List<String>>()
        reader.forEachRow { row -> rows += listOf(row[0], row[1], row[2]) }
        assertEquals(listOf(listOf("x, y", "he said \"hi\"", "3"), listOf("last", "", "")), rows)
    }

    @Test
    fun `header, routes and direction names`() {
        assertEquals("mini", net.feedId)
        assertEquals(LocalDate.of(2026, 10, 5), net.validFrom)
        assertEquals(LocalDate.of(2026, 11, 30), net.validUntil)
        assertEquals(listOf("T1", "B2", "B3"), net.routes.map { it.shortName })
        val t1 = net.routes[route("T1")]
        assertEquals(RouteMode.TRAM, t1.mode)
        assertEquals("007A45", t1.color)
        assertEquals("FR_NAOLIB:Line:T1", t1.id)
        // Most common headsign wins over the short run "Beta".
        assertEquals("Gamma", t1.directionNames[0])
        assertEquals("Alpha", t1.directionNames[1])
        assertNull(net.routes[route("B2")].directionNames[1])
    }

    @Test
    fun `identical trips of two services merge into one unique trip`() {
        // 100-WEEK and 100-WEEKB have identical times -> one unique trip, active in both periods.
        val trips = net.tripsForVehicleJourney(100)
        assertEquals(1, trips.size)
        val t = trips[0]
        assertTrue(net.isTripActive(t, net.dayIndex(LocalDate.of(2026, 10, 13))))
        assertTrue(net.isTripActive(t, net.dayIndex(LocalDate.of(2026, 11, 3))))
        assertFalse(net.isTripActive(t, net.dayIndex(LocalDate.of(2026, 11, 1)))) // Sunday
        assertEquals(t, net.resolveTripId("FR_NAOLIB:VehicleJourney:100-WEEKB:LOC", LocalDate.of(2026, 11, 3)))
        assertNull(net.resolveTripId("FR_NAOLIB:VehicleJourney:999-WEEK", LocalDate.of(2026, 11, 3)))
        // 9 GTFS trips -> 8 unique.
        assertEquals(8, net.tripCount)
    }

    @Test
    fun `calendar exceptions - holiday runs the Sunday service`() {
        val monday = LocalDate.of(2026, 10, 12)
        val alpha = sp(1)
        val holiday = net.timetable(alpha, route("T1"), 0, monday).map { it.time }
        assertEquals(listOf(LocalTime.of(9, 0)), holiday)
        val tuesday = net.timetable(alpha, route("T1"), 0, LocalDate.of(2026, 10, 13))
        assertEquals(
            listOf(LocalTime.of(8, 0), LocalTime.of(8, 30), LocalTime.of(12, 0), LocalTime.of(0, 50)),
            tuesday.map { it.time },
        )
        // After-midnight run sorted last and flagged.
        assertEquals(listOf(false, false, false, true), tuesday.map { it.nextDay })
        assertEquals("Beta", tuesday[2].headsign)
        assertTrue(net.timetable(alpha, route("T1"), 0, LocalDate.of(2027, 1, 1)).isEmpty())
    }

    @Test
    fun `served routes hide terminus-only directions`() {
        // Gamma: T1 dir 0 terminates there (alight only), T1 dir 1 starts there.
        val gamma = net.servedRoutes(sp(3)).associate { net.routes[it.route].shortName to it.directions }
        assertEquals(mapOf("T1" to listOf(1)), gamma)
        // Beta: both T1 directions pass through, B2 starts there.
        val beta = net.servedRoutes(sp(2)).associate { net.routes[it.route].shortName to it.directions }
        assertEquals(mapOf("T1" to listOf(0, 1), "B2" to listOf(0)), beta)
        // Delta is only ever a terminus: linked, but nothing boardable.
        assertTrue(net.servedRoutes(sp(4)).isEmpty())
        assertEquals(listOf("B2", "B3"), net.linkedRoutes(sp(4)).map { net.routes[it].shortName })
    }

    @Test
    fun `scheduled calls include the previous service day's after-midnight runs`() {
        val tuesday = LocalDate.of(2026, 10, 13)
        val base = net.serviceDayStart(LocalDate.of(2026, 10, 14))
        // 00:55 Wednesday wall clock = 24:55 on Tuesday's service day: the 104 run reaches Beta at 25:00.
        val calls = net.scheduledCalls(net.stopPlaces[sp(2)].quays, base + 55 * 60, base + 3600)
        assertEquals(1, calls.size)
        assertEquals(tuesday, calls[0].serviceDay)
        assertEquals(base + 3600, calls[0].departureEpochSecond)
    }

    @Test
    fun `stop places keep their GTFS position and name`() {
        val alpha = net.stopPlaces[net.stopPlaceIndexByNumber(1)!!]
        assertEquals(47.2, alpha.lat, 1e-9)
        assertEquals(-1.56, alpha.lon, 1e-9)
        assertEquals("Epsilon, Nord", net.stopPlaces[net.stopPlaceIndexByNumber(5)!!].name)
    }

    @Test
    fun `shapes are kept per pattern in sequence order`() {
        val p = net.patterns.first { net.routes[it.route].shortName == "T1" && it.directionId == 0 && it.quays.size == 3 }
        val shape = net.shapes[p.shape]
        assertEquals(6, shape.size)
        assertEquals(-1.5601, shape[1], 1e-9)
        assertEquals(-1.5401, shape[5], 1e-9)
    }
}

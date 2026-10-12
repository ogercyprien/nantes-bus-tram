package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.realtime.BoardComposer
import com.bustramnantes.transit.realtime.EntrySource
import com.bustramnantes.transit.realtime.GtfsRealtime
import com.bustramnantes.transit.realtime.Siri
import com.bustramnantes.transit.realtime.SiriVisit
import com.bustramnantes.transit.realtime.StopTimePrediction
import com.bustramnantes.transit.realtime.TripUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.GZIPInputStream

class RealtimeTest {

    private val net = GtfsConverter(
        GtfsSource.directory(File(javaClass.getResource("/gtfs-mini/routes.txt")!!.toURI()).parentFile),
    ).convert()
    private val zone = ZoneId.of("Europe/Paris")
    private val tuesday = LocalDate.of(2026, 10, 13)
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(tuesday, java.time.LocalTime.of(h, m, s)).atZone(zone).toEpochSecond()
    private fun quays(sp: Int) = net.stopPlaces[net.stopPlaceIndexByNumber(sp)!!].quays

    // ------------------------------------------------------------------ SIRI

    @Test
    fun `request body has one StopMonitoringRequest per quay, escaped`() {
        val body = Siri.stopMonitoringRequest(listOf("FR_NAOLIB:Quay:1", "a<b"), "2026-10-12T07:19:15Z")
        assertEquals(2, Regex("<StopMonitoringRequest ").findAll(body).count())
        assertTrue("<MonitoringRef>a&lt;b</MonitoringRef>" in body)
        assertTrue("<RequestorRef>opendata</RequestorRef>" in body)
    }

    @Test
    fun `parses a captured Okina StopMonitoring delivery`() {
        val xml = javaClass.getResource("/okina/siri_stop_monitoring.xml")!!.readText()
        val visits = Siri.parseStopMonitoring(xml.reader(), KXmlParser())
        assertTrue(visits.size >= 5)
        val first = visits.first()
        assertEquals("FR_NAOLIB:Quay:156", first.monitoringRef)
        assertEquals("FR_NAOLIB:Line:1", first.lineRef)
        assertEquals("1", first.lineShortName)
        assertEquals("FR_NAOLIB:VehicleJourney:53090358-CR_26_27-EW27BE11-Lu-Ma-Je-00-1101000", first.vehicleJourneyRef)
        assertEquals("R", first.directionName)
        assertEquals("FR_NAOLIB:Quay:2770", first.destinationRef)
        assertEquals("Babinière", first.destinationName)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-12T09:16:00+02:00").toEpochSecond(), first.aimedDeparture)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-12T09:19:46+02:00").toEpochSecond(), first.expectedDeparture)
        assertTrue(first.monitored)
        assertEquals(first.expectedDeparture, first.bestTime)
    }

    // ------------------------------------------------------------------ GTFS-RT

    @Test
    fun `decodes a captured Okina TripUpdates feed`() {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/okina/trip_updates.pb.gz")!!).readBytes()
        val feed = GtfsRealtime.decodeTripUpdates(bytes)
        assertTrue("header timestamp", feed.timestamp > 1_790_000_000L)
        assertTrue("trip count ${feed.updates.size}", feed.updates.size > 100)
        assertTrue(feed.updates.all { it.tripId.startsWith("FR_NAOLIB:VehicleJourney:") })
        val stop = feed.updates.flatMap { it.stopTimes }.first { it.stopId != null }
        assertTrue(stop.stopId!!.startsWith("FR_NAOLIB:Quay:"))
        assertTrue(stop.arrivalTime != null || stop.departureTime != null || stop.departureDelay != null)
    }

    // ------------------------------------------------------------------ composition

    private fun visit(
        vj: String?,
        quay: String,
        time: Long,
        destinationRef: String? = null,
        line: String = "T1",
        dir: String = "A",
        dest: String = "Gamma",
        aimed: Long = time,
    ) = SiriVisit(quay, "FR_NAOLIB:Line:$line", vj, dir, destinationRef, dest, aimed, time, aimed, time, true)

    @Test
    fun `SIRI board - real-time part replaces the timetabled trip, the rest stays theoretical`() {
        val composer = BoardComposer(net, horizonSeconds = 3600)
        val now = at(7, 55)
        // 100-WEEK reaches Alpha 08:00 scheduled, SIRI predicts 08:01:30.
        val visits = listOf(visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:11", at(8, 1, 30), aimed = at(8, 0)))
        val board = composer.composeWithSiri(quays(1), visits, now)
        assertEquals(listOf(at(8, 1, 30), at(8, 30)), board.map { it.departureEpochSecond })
        val rt = board[0]
        assertTrue(rt.isRealtime)
        assertEquals(EntrySource.SIRI, rt.source)
        assertEquals(net.routeIndex("T1"), rt.route)
        assertEquals(0, rt.directionId)
        assertEquals(at(8, 0), rt.scheduledEpochSecond)
        assertFalse(board[1].isRealtime)
        assertEquals(EntrySource.TIMETABLE, board[1].source)
    }

    @Test
    fun `SIRI board drops trips terminating at the stop and departed vehicles`() {
        val composer = BoardComposer(net, horizonSeconds = 3600)
        val now = at(8, 6)
        val visits = listOf(
            // Ends at Gamma: dropped at Gamma.
            visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:31", at(8, 10), destinationRef = "FR_NAOLIB:Quay:31"),
            // Running early: left a minute ago although timetabled 08:30.
            visit("FR_NAOLIB:VehicleJourney:110-WEEK", "FR_NAOLIB:Quay:32", at(8, 5), dir = "R", aimed = at(8, 30)),
        )
        val board = composer.composeWithSiri(quays(3), visits, now)
        assertTrue(board.none { it.source == EntrySource.SIRI })
        // 110-WEEK was listed by SIRI (so it isn't re-added from the timetable) but has left.
        assertTrue(board.none { it.departureEpochSecond == at(8, 30) && it.directionId == 1 })
    }

    @Test
    fun `real-time flag needs SIRI presence within the horizon`() {
        val composer = BoardComposer(net, horizonSeconds = 7200, realtimeHorizonSeconds = 50 * 60)
        val now = at(7, 30)
        val visits = listOf(
            visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:11", at(8, 0)), // 30 min: RT
            visit("FR_NAOLIB:VehicleJourney:101-WEEK", "FR_NAOLIB:Quay:11", at(8, 30)), // 60 min: listed, not RT
        )
        val board = composer.composeWithSiri(quays(1), visits, now).filter { it.source == EntrySource.SIRI }
        assertEquals(listOf(true, false), board.map { it.isRealtime })
    }

    @Test
    fun `unknown trip is still shown from SIRI's own fields`() {
        val composer = BoardComposer(net, horizonSeconds = 3600)
        val now = at(7, 55)
        val visits = listOf(visit("FR_NAOLIB:VehicleJourney:999-NEW", "FR_NAOLIB:Quay:21", at(8, 3), line = "B2", dir = "R", dest = "Nouveau"))
        val e = composer.composeWithSiri(quays(2), visits, now).first { it.source == EntrySource.SIRI }
        assertEquals(net.routeIndex("B2"), e.route)
        assertEquals(1, e.directionId)
        assertEquals("Nouveau", e.destination)
        assertEquals("FR_NAOLIB:VehicleJourney:999-NEW", e.tripKey)
    }

    @Test
    fun `GTFS-RT overlay applies predicted times and propagates delays`() {
        val composer = BoardComposer(net, horizonSeconds = 3600)
        val now = at(7, 55)
        val updates = mapOf(
            // Prediction at Alpha itself.
            "FR_NAOLIB:VehicleJourney:100-WEEK" to TripUpdate(
                "FR_NAOLIB:VehicleJourney:100-WEEK",
                listOf(StopTimePrediction(1, "FR_NAOLIB:Quay:11", null, null, at(8, 2), 120)),
            ),
        )
        val alpha = composer.composeWithTripUpdates(quays(1), updates, now)
        assertEquals(at(8, 2), alpha.first().departureEpochSecond)
        assertEquals(EntrySource.GTFS_RT, alpha.first().source)
        assertTrue(alpha.first().isRealtime)
        // At Beta (no stop update there) the +120 s delay propagates: 08:05 -> 08:07.
        val beta = composer.composeWithTripUpdates(quays(2), updates, now)
        val propagated = beta.first { it.source == EntrySource.GTFS_RT }
        assertEquals(at(8, 7), propagated.departureEpochSecond)
        // Trips absent from the feed stay theoretical.
        assertTrue(beta.filter { it.source == EntrySource.TIMETABLE }.none { it.isRealtime })
    }

    @Test
    fun `direction letters map to GTFS direction ids`() {
        assertEquals(0, BoardComposer.directionFromSiri("A"))
        assertEquals(1, BoardComposer.directionFromSiri("R"))
        assertNull(BoardComposer.directionFromSiri("X"))
    }
}

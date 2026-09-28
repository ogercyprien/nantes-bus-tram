package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.raptor.AccessPoint
import com.bustramnantes.transit.raptor.RaptorLeg
import com.bustramnantes.transit.raptor.RaptorRouter
import com.bustramnantes.transit.raptor.TripInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RaptorTest {

    private val net = GtfsConverter(
        GtfsSource.directory(File(javaClass.getResource("/gtfs-mini/routes.txt")!!.toURI()).parentFile),
    ).convert()
    private val router = RaptorRouter(net)
    private val zone = ZoneId.of("Europe/Paris")
    private val tuesday = LocalDate.of(2026, 10, 13)

    private fun at(date: LocalDate, h: Int, m: Int) = LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(zone).toEpochSecond()
    private fun quays(stopPlace: Int) = net.stopPlaces[net.stopPlaceIndexByNumber(stopPlace)!!].quays.map { AccessPoint(it, 0) }
    private fun hm(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().toString()

    @Test
    fun `direct trip`() {
        val js = router.departAfter(quays(1), quays(3), at(tuesday, 7, 55), maxJourneys = 1)
        assertEquals(1, js.size)
        val j = js[0]
        assertEquals("08:00", hm(j.departureEpochSecond))
        assertEquals("08:10", hm(j.arrivalEpochSecond))
        assertEquals(0, j.transfers)
        val ride = j.rides.single()
        assertEquals(TripInstance(net.tripsForVehicleJourney(100).single(), tuesday), ride.trip)
        assertEquals(0, ride.boardPosition)
        assertEquals(2, ride.alightPosition)
    }

    @Test
    fun `range search returns the following departures too`() {
        val js = router.departAfter(quays(1), quays(3), at(tuesday, 7, 55), maxJourneys = 3)
        assertEquals(listOf("08:00", "08:30"), js.map { hm(it.departureEpochSecond) })
    }

    @Test
    fun `walking transfer beats a same-quay transfer`() {
        // T1 to Gamma 08:10, walk ~200 m to Epsilon, B3 08:20 -> Delta 08:28; the B2 change at Beta
        // only reaches Delta at 08:35.
        val j = router.departAfter(quays(1), quays(4), at(tuesday, 7, 55), maxJourneys = 1).single()
        assertEquals("08:28", hm(j.arrivalEpochSecond))
        assertEquals(1, j.transfers)
        val transfer = j.legs.filterIsInstance<RaptorLeg.Transfer>().single()
        assertEquals(net.quayIndex("FR_NAOLIB:Quay:31"), transfer.fromQuay)
        assertEquals(net.quayIndex("FR_NAOLIB:Quay:51"), transfer.toQuay)
        assertTrue(transfer.toEpochSecond - transfer.fromEpochSecond in 150..300)
    }

    @Test
    fun `same-quay transfer when no walk is shorter`() {
        // To Delta from Beta's other side: only B2 from quay 21 works.
        val j = router.departAfter(quays(1), listOf(AccessPoint(net.quayIndex("FR_NAOLIB:Quay:41")!!, 0)), at(tuesday, 7, 55), excluded = setOf(TripInstance(net.tripsForVehicleJourney(300).single(), tuesday))).first()
        assertEquals("08:35", hm(j.arrivalEpochSecond))
        assertTrue(j.legs.none { it is RaptorLeg.Transfer })
    }

    @Test
    fun `arrive by picks the latest departure`() {
        val js = router.arriveBy(quays(1), quays(3), at(tuesday, 8, 45), maxJourneys = 2)
        assertEquals(listOf("08:30", "08:00"), js.map { hm(it.departureEpochSecond) })
        assertEquals("08:42", hm(js[0].arrivalEpochSecond))
        assertTrue(js[0].legs.first() is RaptorLeg.Access)
        assertTrue(js[0].legs.last() is RaptorLeg.Egress)
    }

    @Test
    fun `arrive by with a walking transfer`() {
        val j = router.arriveBy(quays(1), quays(4), at(tuesday, 8, 30), maxJourneys = 1).single()
        assertEquals("08:00", hm(j.departureEpochSecond))
        assertEquals("08:28", hm(j.arrivalEpochSecond))
        val transfer = j.legs.filterIsInstance<RaptorLeg.Transfer>().single()
        assertEquals(net.quayIndex("FR_NAOLIB:Quay:31"), transfer.fromQuay)
    }

    @Test
    fun `after-midnight run belongs to the previous service day`() {
        val wednesday = tuesday.plusDays(1)
        val j = router.departAfter(quays(1), quays(3), at(wednesday, 0, 40), maxJourneys = 1).single()
        assertEquals("00:50", hm(j.departureEpochSecond))
        assertEquals("01:10", hm(j.arrivalEpochSecond))
        assertEquals(tuesday, j.rides.single().trip.serviceDay)
    }

    @Test
    fun `holiday runs the Sunday service and dates outside the feed have none`() {
        val holiday = LocalDate.of(2026, 10, 12)
        val j = router.departAfter(quays(1), quays(3), at(holiday, 7, 55), maxJourneys = 1).single()
        assertEquals("09:00", hm(j.departureEpochSecond))
        assertTrue(router.departAfter(quays(1), quays(3), at(LocalDate.of(2027, 1, 5), 7, 55)).isEmpty())
    }

    @Test
    fun `excluded trip is never boarded`() {
        val banned = TripInstance(net.tripsForVehicleJourney(100).single(), tuesday)
        val j = router.departAfter(quays(1), quays(3), at(tuesday, 7, 55), maxJourneys = 1, excluded = setOf(banned)).single()
        assertEquals("08:30", hm(j.departureEpochSecond))
    }

    @Test
    fun `access walk starts just in time for the first ride`() {
        val access = listOf(AccessPoint(net.quayIndex("FR_NAOLIB:Quay:11")!!, 300, 400))
        val j = router.departAfter(access, quays(3), at(tuesday, 7, 0), maxJourneys = 1).single()
        assertEquals("07:55", hm(j.departureEpochSecond))
        assertEquals(400, (j.legs.first() as RaptorLeg.Access).meters)
    }
}

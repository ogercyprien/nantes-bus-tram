package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.BacsDeLoire
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.gtfs.appending
import com.bustramnantes.transit.raptor.AccessPoint
import com.bustramnantes.transit.raptor.RaptorLeg
import com.bustramnantes.transit.raptor.RaptorRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The Bacs de Loire GTFS (captured live) folded into the `gtfs-mini` Naolib fixture. */
class BacsDeLoireTest {

    private fun dir(name: String) = File(javaClass.getResource("/$name/routes.txt")!!.toURI()).parentFile
    private val mini = GtfsConverter(GtfsSource.directory(dir("gtfs-mini"))).convert()
    private val net = GtfsConverter(GtfsSource.directory(dir("gtfs-mini")).appending(GtfsSource.directory(dir("bacs-loire")), BacsDeLoire.SPEC)).convert()

    @Test
    fun `bacs become lines B1 and B2 with their own landings, Naolib data untouched`() {
        assertEquals(mini.validFrom, net.validFrom)
        assertEquals(mini.dayCount, net.dayCount)
        val b1 = net.routes.first { it.shortName == "B1" }
        assertEquals("FR_NAOLIB:Line:B1", b1.id)
        assertEquals("Bac de Loire · Basse-Indre ↔ Indret", b1.longName)
        assertEquals(RouteMode.FERRY, b1.mode)
        assertEquals(setOf("Indret", "Basse-Indre"), b1.directionNames.filterNotNull().toSet())
        val landings = net.stopPlaces.filter { it.number >= TransitNetwork.EXTERNAL_NUMBER_BASE }
        assertEquals(listOf("Le Pellerin", "Couëron", "Basse-Indre", "Indret"), landings.map { it.name })
        assertEquals("FR_NAOLIB:StopPlace:9900003", landings[2].id)
        assertTrue(landings.all { sp -> sp.quays.all(net::isExternalQuay) })
        // The fixture's own bus "B2" keeps its name; the bac B2 becomes "Bac B2".
        assertEquals(net.routes.indices.filter { net.routes[it].shortName in setOf("B1", "Bac B2") }.toSet(), net.externalRoutes)
        assertEquals("Beta - Delta", net.routes.single { it.shortName == "B2" }.longName)
        assertEquals(mini.tripCount, (0 until net.tripCount).count { net.patterns[net.tripPattern[it]].route !in net.externalRoutes })
    }

    @Test
    fun `frequencies expand to a crossing every 20 min, end exclusive`() {
        val zone = ZoneId.of(net.timezone)
        val tuesday = LocalDate.of(2026, 10, 13)
        val basseIndre = net.stopPlaces.first { it.name == "Basse-Indre" }.quays
        val from = tuesday.atStartOfDay(zone).toEpochSecond()
        val times = net.scheduledCalls(basseIndre, from, from + 86_400)
            .filter { it.serviceDay == tuesday }
            .map { (it.departureEpochSecond - from).toInt() }.sorted()
        assertEquals(5 * 3600 + 30 * 60, times.first())
        assertEquals(21 * 3600 + 50 * 60, times.last()) // 22:10 is the window's end
        assertTrue(times.zipWithNext().all { (a, b) -> b - a == 1200 })
    }

    @Test
    fun `RAPTOR crosses the Loire on the bac`() {
        val zone = ZoneId.of(net.timezone)
        val q = { name: String -> net.stopPlaces.first { it.name == name }.quays.single() }
        val router = RaptorRouter(net)
        val t = LocalDate.of(2026, 10, 13).atTime(8, 1).atZone(zone).toEpochSecond()
        val j = router.departAfter(listOf(AccessPoint(q("Basse-Indre"), 0)), listOf(AccessPoint(q("Indret"), 0)), t).first()
        val ride = j.legs.filterIsInstance<RaptorLeg.Ride>().single()
        assertEquals("B1", net.routes[net.patterns[ride.pattern].route].shortName)
        assertEquals(LocalDate.of(2026, 10, 13).atTime(8, 10).atZone(zone).toEpochSecond(), ride.fromEpochSecond)
        assertEquals(600, ride.toEpochSecond - ride.fromEpochSecond)
    }
}

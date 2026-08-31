package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrosswalkTest {

    private val net = GtfsConverter(
        GtfsSource.directory(File(javaClass.getResource("/gtfs-mini/routes.txt")!!.toURI()).parentFile),
    ).convert()

    private fun place(number: Int, n: TransitNetwork) = n.stopPlaces[n.stopPlaceIndexByNumber(number)!!]

    @Test
    fun `name match within radius, punctuation and saint folding`() {
        assertEquals(Crosswalk.looseName("Sainte-Luce"), Crosswalk.looseName("Ste Luce"))
        assertEquals(Crosswalk.looseName("Viarme - Talensac"), Crosswalk.looseName("Viarme-Talensac"))
        assertNotEquals(Crosswalk.looseName("Lieu Unique"), Crosswalk.looseName("Duchesse Anne - Château"))

        val official = listOf(
            OfficialStop("100", "ALPHA", 47.20005, -1.56005, setOf("T1")),
            // Accent/case differences still match.
            OfficialStop("200", "Béta", 47.2000, -1.5500, setOf("T1", "B2")),
            // Same name as Gamma but 9 km away: must not match.
            OfficialStop("300", "Gamma", 47.28, -1.54, setOf("T1")),
        )
        val n = net.withCrosswalk(Crosswalk.build(net, official))
        val alpha = place(1, n)
        assertTrue(alpha.hasDisplayPosition)
        assertEquals(47.20005, alpha.lat, 1e-9)
        assertTrue(place(2, n).hasDisplayPosition)
        assertEquals(47.2000, place(2, n).lat, 1e-9)
        assertFalse(place(3, n).hasDisplayPosition)
    }

    @Test
    fun `a split official stop keeps GTFS positions for the stop matched by lines`() {
        // Official "Gamma" covered Gamma (T1) and Epsilon (B3), ~200 m apart. Gamma takes it by name,
        // Epsilon by lines (B3 subset of the official line set, within 150 m of Epsilon).
        val official = listOf(OfficialStop("500", "Gamma", 47.2010, -1.5400, setOf("T1", "B3")))
        val n = net.withCrosswalk(Crosswalk.build(net, official))
        assertTrue(place(3, n).hasDisplayPosition)
        assertEquals(47.2010, place(3, n).lat, 1e-9)
        // Epsilon matched by lines, so it keeps its own GTFS position.
        assertFalse(place(5, n).hasDisplayPosition)
        assertEquals(47.2018, place(5, n).lat, 1e-9)
    }

    @Test
    fun `crosswalk survives a round trip through crosswalk()`() {
        val official = listOf(OfficialStop("100", "Alpha", 47.20005, -1.56005, setOf("T1")))
        val n = net.withCrosswalk(Crosswalk.build(net, official))
        val again = net.withCrosswalk(n.crosswalk())
        assertEquals(place(1, n).lat, place(1, again).lat, 1e-12)
        assertEquals(place(3, n).lat, place(3, again).lat, 1e-12)
        assertEquals(place(1, n).hasDisplayPosition, place(1, again).hasDisplayPosition)
    }
}

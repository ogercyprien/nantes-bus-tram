package com.bustramnantes.app.presentation.itinerary.map

import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.PathLink
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test journeys: a walk, then rides, built from (lat, lon) lists. */
internal object JourneyFixtures {
    private val t0 = Instant.parse("2026-11-09T08:00:00Z")

    fun wkt(points: List<Pair<Double, Double>>) = points.joinToString(prefix = "LINESTRING (", postfix = ")") { (la, lo) -> "$lo $la" }

    fun walk(vararg points: Pair<Double, Double>) = JourneySection.Walking(
        pathLinks = listOf(PathLink(wkt(points.toList()))), magneticDirection = 0.0, durationSeconds = 300, distanceMeters = 300,
    )

    fun ride(line: String, color: String, path: List<Pair<Double, Double>>, stops: List<Pair<Double, Double>>) = JourneySection.Transit(
        lineId = "FR_NAOLIB:Line:$line", lineNumber = line, lineColor = color, lineTextColor = "FFFFFF",
        transportMode = TransportMode.BUS, directionName = "Terminus",
        concernedStops = stops.mapIndexed { i, (la, lo) -> ConcernedStop("FR_NAOLIB:StopPlace:${line.hashCode() + i}", "Stop $i", la, lo, t0) },
        tripGeometryWkt = wkt(path), timeSourceType = TimeSourceType.THEORICAL, departureTime = t0, arrivalTime = t0,
    )

    fun journey(vararg sections: JourneySection) = Journey(sections.toList(), 1800, t0, t0.plus(kotlin.time.Duration.parse("30m")), 0)
}

class JourneyMapModelTest {

    @Test
    fun `legs keep their geometry and colours, stops are marked, A and B are the ends`() {
        val j = JourneyFixtures.journey(
            JourneyFixtures.walk(47.20 to -1.55, 47.201 to -1.549),
            JourneyFixtures.ride(
                "C2", "e2001a",
                path = listOf(47.201 to -1.549, 47.205 to -1.548, 47.21 to -1.545, 47.215 to -1.54),
                stops = listOf(47.201 to -1.549, 47.21 to -1.545, 47.215 to -1.54),
            ),
            JourneyFixtures.walk(47.215 to -1.54, 47.216 to -1.539),
        )
        val model = journeyMapModel(j)!!
        assertEquals(3, model.legs.size)
        assertTrue(model.legs[0].isWalk)
        val ride = model.legs[1]
        assertFalse(ride.isWalk)
        assertEquals("#E2001A", ride.color)
        assertEquals("C2", ride.lineNumber)
        assertEquals(4, ride.points.size) // the street path, not the 3 stops
        assertEquals(listOf(true, false, true), model.stops.map { it.isTransfer })
        assertEquals(MapPoint(47.20, -1.55), model.origin)
        assertEquals(MapPoint(47.216, -1.539), model.destination)
    }

    @Test
    fun `a ride without geometry falls back to its stops, nothing drawable is null`() {
        val noGeometry = JourneyFixtures.ride("3", "#0076BD", path = emptyList(), stops = listOf(47.2 to -1.5, 47.21 to -1.51))
            .copy(tripGeometryWkt = "")
        assertEquals(2, journeyMapModel(JourneyFixtures.journey(noGeometry))!!.legs.single().points.size)
        assertNull(journeyMapModel(JourneyFixtures.journey()))
    }

    @Test
    fun `colours are normalised`() {
        assertEquals("#007A45", hexColor("007a45"))
        assertEquals("#007A45", hexColor("#007A45"))
        assertEquals("#5C6BC0", hexColor(""))
        assertEquals("#FFFFFF", hexColor("zzz", fallback = "#FFFFFF"))
    }

    @Test
    fun `badge midpoint is halfway along the path`() {
        val mid = ItineraryMapLayers.midpoint(listOf(MapPoint(47.0, -1.0), MapPoint(47.0, -0.99), MapPoint(47.01, -0.99)))
        // Leg 1 ≈ 0.0068 (lon × cos 47°), leg 2 = 0.01: halfway lies on the second leg.
        assertEquals(-0.99, mid.lon, 1e-9)
        assertTrue(mid.lat > 47.0 && mid.lat < 47.01)
    }
}

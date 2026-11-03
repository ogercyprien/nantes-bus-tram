package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.presentation.common.utils.parseWktLineString
import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.haversineMeters
import com.bustramnantes.transit.walk.WalkGraphFormat
import java.io.File
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertTrue
import org.junit.Test

/** [planJourneys] on the bundled seed network + OSM walking graph: walks and rides follow the streets. */
class StreetWalkPlanningTest {

    private val network = File("src/main/assets/seed/network.bin").inputStream().use(NetworkFormat::read)
    private val index = NetworkIndex(network) { File("src/main/assets/seed/walk_graph.bin").inputStream().use(WalkGraphFormat::read) }

    /** A Tuesday inside the seed feed, 10:00. */
    private val tuesday10: ItineraryTimePreference.DepartAt = run {
        var d = LocalDate.parse(network.validFrom.plusDays(7).toString())
        while (d.dayOfWeek.ordinal != 1) d = LocalDate.fromEpochDays(d.toEpochDays() + 1)
        ItineraryTimePreference.DepartAt(LocalDateTime(d, LocalTime(10, 0)))
    }

    @Test
    fun `the seed network carries street footpaths`() {
        assertTrue(network.footpaths != null && network.footpaths!!.pairCount > 20_000)
    }

    @Test
    fun `short walk-only option follows the streets`() {
        // Place Royale -> Place du Commerce (~450 m crow-fly).
        val js = planJourneys(index, 47.2153, -1.5603, 47.2133, -1.5560, tuesday10)
        val walk = js.single { j -> j.sections.all { it is JourneySection.Walking } }.sections.single() as JourneySection.Walking
        val points = parseWktLineString(walk.pathLinks.single().geometryWkt)
        assertTrue("street path has ${points.size} points", points.size > 2)
        assertTrue(walk.distanceMeters >= haversineMeters(47.2153, -1.5603, 47.2133, -1.5560))
    }

    @Test
    fun `no walk-only option across the Loire where crow-fly would allow one`() {
        // Quai de la Fosse -> Trentemoult: 1.2 km crow-fly (18 min at 4 km/h × 1.3), ~2.9 km on foot.
        val js = planJourneys(index, 47.2085, -1.5690, 47.1975, -1.5720, tuesday10)
        assertTrue(js.none { j -> j.sections.all { it is JourneySection.Walking } })
        assertTrue(js.isNotEmpty())
    }

    @Test
    fun `rides follow the streets, through every stop`() {
        assertTrue("seed has ride shapes", (network.rideShapes?.size ?: 0) > 2_500)
        // Gare Sud -> Le Cardo: C2 and trams.
        val js = planJourneys(index, 47.2173, -1.5410, 47.2600, -1.5570, tuesday10)
        val rides = js.flatMap { j -> j.sections.filterIsInstance<JourneySection.Transit>() }
        assertTrue(rides.isNotEmpty())
        for (ride in rides) {
            val points = parseWktLineString(ride.tripGeometryWkt)
            assertTrue("${ride.lineNumber}: ${points.size} points for ${ride.concernedStops.size} stops", points.size > ride.concernedStops.size)
            for (stop in ride.concernedStops) assertTrue(points.any { (la, lo) -> la == stop.lat && lo == stop.lon })
            val pathLength = points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }
            val stopToStop = ride.concernedStops.zipWithNext().sumOf { (a, b) -> haversineMeters(a.lat, a.lon, b.lat, b.lon) }
            assertTrue("${ride.lineNumber}: $pathLength vs $stopToStop", pathLength >= stopToStop && pathLength < stopToStop * 1.8)
        }
    }
}

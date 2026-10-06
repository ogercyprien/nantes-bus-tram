package com.bustramnantes.app.presentation.itinerary.share

import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the pure itinerary share-text builders (text share, calendar events). */
class JourneyShareTextTest {

    private val zone = TimeZone.of("Europe/Paris")

    private fun at(hour: Int, minute: Int, day: Int = 10): Instant =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(zone)

    private fun stop(name: String, time: Instant? = null) = ConcernedStop(
        stopId = name.hashCode().toString(),
        name = name,
        lat = 47.2,
        lon = -1.55,
        departureTime = time,
    )

    private fun ride(
        line: String,
        from: String,
        to: String,
        direction: String,
        dep: Instant,
        arr: Instant,
        mode: TransportMode = TransportMode.BUS,
        phantom: Boolean = false,
    ) = JourneySection.Transit(
        lineId = line,
        lineNumber = line,
        lineColor = "#000000",
        lineTextColor = "#FFFFFF",
        transportMode = mode,
        directionName = direction,
        concernedStops = listOf(stop(from, dep), stop("Intermédiaire"), stop(to, arr)),
        tripGeometryWkt = "",
        timeSourceType = TimeSourceType.REALTIME,
        departureTime = dep,
        arrivalTime = arr,
        isSuspectedPhantom = phantom,
    )

    private fun walk(durationSeconds: Int) = JourneySection.Walking(
        pathLinks = emptyList(),
        magneticDirection = 0.0,
        durationSeconds = durationSeconds,
        distanceMeters = 150,
    )

    /** The example from the feature request: stop → stop, two rides joined by a walk. */
    private val journey = Journey(
        sections = listOf(
            ride("C2", "Rond-Point de Rennes", "Talensac", "Gare Sud", at(15, 2), at(15, 8)),
            walk(120),
            ride("12", "Place du cirque", "Foch Cathédrale", "Haluchère-Batignolles", at(15, 19), at(15, 23)),
        ),
        totalDurationSeconds = 21 * 60,
        departureTime = at(15, 2),
        arrivalTime = at(15, 23),
        transferCount = 1,
    )

    @Test
    fun `share text matches the requested format`() {
        val text = buildJourneyShareText(
            journey, "Rond-Point de Rennes", "Foch Cathédrale", zone, today = LocalDate(2026, 10, 10),
        )
        assertEquals(
            """
            Trajet Rond-Point de Rennes → Foch Cathédrale
            - Bus C2 Rond-Point de Rennes → Talensac (direction Gare Sud), 15:02-15:08
            - Marche Talensac → Place du cirque, 15:08-15:10
            - Bus 12 Place du cirque → Foch Cathédrale (direction Haluchère-Batignolles), 15:19-15:23
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `share text adds the date line when the trip is not today`() {
        val text = buildJourneyShareText(
            journey, "Rond-Point de Rennes", "Foch Cathédrale", zone, today = LocalDate(2026, 10, 9),
        )
        assertEquals("Le samedi 10 octobre 2026", text.lines()[1])
        assertEquals(5, text.lines().size)
    }

    @Test
    fun `address endpoints label the access and egress walks`() {
        val withWalks = journey.copy(
            sections = listOf(walk(300)) + journey.sections + walk(240),
            departureTime = at(14, 57),
            arrivalTime = at(15, 27),
        )
        val lines = buildJourneyShareText(withWalks, "12 rue de Strasbourg", "Ma position", zone)
            .lines()
        assertEquals("Trajet 12 rue de Strasbourg → Ma position", lines[0])
        assertEquals("- Marche 12 rue de Strasbourg → Rond-Point de Rennes, 14:57-15:02", lines[1])
        assertEquals("- Marche Foch Cathédrale → Ma position, 15:23-15:27", lines.last())
    }

    @Test
    fun `blank labels fall back to the first and last stops`() {
        assertEquals("Trajet Rond-Point de Rennes → Foch Cathédrale", journeyTitle(journey, "", " "))
    }

    @Test
    fun `tram mode and phantom flag are spelled out`() {
        val tram = ride(
            "1", "Commerce", "Gare Nord", "Ranzay", at(9, 0), at(9, 6),
            mode = TransportMode.TRAM, phantom = true,
        )
        val leg = Journey(listOf(tram), 360, at(9, 0), at(9, 6), 0).toShareLegs("", "").single()
        assertEquals(
            "Tram 1 Commerce → Gare Nord (direction Ranzay), 09:00-09:06 - probablement supprimé",
            formatShareLeg(leg, zone),
        )
    }

    @Test
    fun `navibus lines are labelled Navibus, night buses stay Bus`() {
        // GTFS route_type 4 (ferry) = the Navibus boats; NA/NC/... are night buses (type 3).
        val boat = ride("N2", "Port Boyer", "Petit Port Facultés", "Petit Port", at(15, 2), at(15, 8))
            .copy(transportMode = TransportMode.FERRY)
        assertEquals("Navibus N2", transitLabel(boat))
        assertEquals("Bus NA", transitLabel(boat.copy(lineNumber = "NA", transportMode = TransportMode.BUS)))
        assertEquals("Tram 1", transitLabel(boat.copy(lineNumber = "1", transportMode = TransportMode.TRAM)))
    }

    @Test
    fun `whole-trip calendar event lists every leg`() {
        val event = buildJourneyCalendarEvent(journey, "Rond-Point de Rennes", "Foch Cathédrale", zone)
        assertEquals("Trajet Rond-Point de Rennes → Foch Cathédrale", event.title)
        assertEquals(at(15, 2), event.start)
        assertEquals(at(15, 23), event.end)
        assertEquals("Rond-Point de Rennes", event.location)
        assertEquals(
            listOf(
                "Bus C2 Rond-Point de Rennes → Talensac (direction Gare Sud), 15:02-15:08",
                "Marche Talensac → Place du cirque, 15:08-15:10",
                "Bus 12 Place du cirque → Foch Cathédrale (direction Haluchère-Batignolles), 15:19-15:23",
            ),
            event.description?.lines(),
        )
    }

    @Test
    fun `per-leg calendar events are titled by line and alighting stop`() {
        val events = journey.toShareLegs("", "")
            .filterIsInstance<ShareLeg.Ride>()
            .map { buildLegCalendarEvent(it, zone) }
        assertEquals(listOf("Bus C2 → Talensac", "Bus 12 → Foch Cathédrale"), events.map { it.title })
        assertEquals(listOf("Rond-Point de Rennes", "Place du cirque"), events.map { it.location })
        assertEquals(at(15, 19), events[1].start)
        assertEquals(at(15, 23), events[1].end)
    }

    @Test
    fun `ride without stops still reads cleanly`() {
        val bare = ride("C2", "x", "y", "", at(15, 2), at(15, 8)).copy(concernedStops = emptyList())
        val leg = Journey(listOf(bare), 360, at(15, 2), at(15, 8), 0).toShareLegs("", "").single()
        assertEquals("Bus C2, 15:02-15:08", formatShareLeg(leg, zone))
        assertNull(buildLegCalendarEvent(leg as ShareLeg.Ride, zone).location)
    }

    @Test
    fun `french date formatting`() {
        assertEquals("jeudi 1er octobre 2026", formatFrenchDate(LocalDate(2026, 10, 1)))
        assertEquals("jeudi 24 décembre 2026", formatFrenchDate(LocalDate(2026, 12, 24)))
    }
}

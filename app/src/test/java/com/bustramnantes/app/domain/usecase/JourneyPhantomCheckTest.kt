package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.repository.ItineraryRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import com.bustramnantes.app.domain.model.StopBoard
import com.bustramnantes.app.domain.model.DepartureSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPhantomCheckTest {

    /** 18:00:00 Paris. */
    private val now = Instant.parse("2026-10-05T16:00:00Z")

    private fun t(hms: String) = Instant.parse("2026-10-05T$hms+02:00")

    private fun leg(
        dep: String,
        arr: String,
        line: String = "23",
        directionId: Int = 1,
        stop: String = "59772",
        source: TimeSourceType = TimeSourceType.THEORICAL,
        tripId: String? = "trip-$dep",
    ) = JourneySection.Transit(
        lineId = line, lineNumber = line, lineColor = "", lineTextColor = "", transportMode = TransportMode.BUS,
        directionName = "Haluchère", concernedStops = listOf(ConcernedStop(stop, "Place du Cirque", 0.0, 0.0, null)),
        tripGeometryWkt = "", timeSourceType = source, departureTime = t(dep), arrivalTime = t(arr),
        directionId = directionId, tripId = tripId,
    )

    private fun walk(seconds: Int) = JourneySection.Walking(emptyList(), 0.0, seconds, 0)

    private fun journey(start: String, end: String, vararg sections: JourneySection) =
        Journey(sections.toList(), 0, t(start), t(end), 0)

    private fun board(hms: String, rt: Boolean, phantom: Boolean = false, line: String = "23", code: String = "0", tripId: String? = null) =
        Departure(
            lineId = line, lineNumber = line, lineName = line, lineColor = "", lineTextColor = "",
            transportMode = TransportMode.BUS, direction = Direction(code, "Haluchère"),
            nextDepartedTime = t(hms), nextArrivalTime = t(hms), isRealtime = rt,
            tripId = tripId, isSuspectedPhantom = phantom,
        )

    // --- matching a leg to its bus on the board -------------------------------------------------

    @Test
    fun `a theoretical leg matches the theoretical slot, not the prediction next to it`() {
        val slot = board("18:10:00", rt = false, phantom = true)
        val prediction = board("18:10:30", rt = true)
        assertEquals(slot, matchLegOnBoard(leg("18:10:00", "18:30:00"), listOf(prediction, slot)))
        val rtLeg = leg("18:10:00", "18:30:00", source = TimeSourceType.REALTIME)
        assertEquals(prediction, matchLegOnBoard(rtLeg, listOf(prediction, slot)))
    }

    @Test
    fun `no match on another line, another direction or too far in time`() {
        val l = leg("18:10:00", "18:30:00")
        assertNull(matchLegOnBoard(l, listOf(board("18:10:00", rt = false, line = "C1"))))
        assertNull(matchLegOnBoard(l, listOf(board("18:10:00", rt = false, code = "1"))))
        assertNull(matchLegOnBoard(l, listOf(board("18:12:00", rt = false))))
    }

    @Test
    fun `only legs departing within the hour are tagged`() {
        val soon = journey("18:00:00", "18:40:00", leg("18:10:00", "18:40:00"))
        val later = journey("19:20:00", "19:50:00", leg("19:20:00", "19:50:00"))
        val boards = mapOf("59772" to listOf(board("18:10:00", rt = false, phantom = true), board("19:20:00", rt = false, phantom = true)))
        val (a, b) = tagPhantomLegs(listOf(soon, later), boards, now)
        assertTrue(a.usesSuspectedPhantom)
        assertFalse("beyond the real-time horizon nothing can be told apart", b.usesSuspectedPhantom)
    }

    @Test
    fun `a trip id match wins over a closer departure of the same line`() {
        val l = leg("18:10:00", "18:30:00", tripId = "42@2026-10-05")
        val mine = board("18:11:20", rt = true, tripId = "42@2026-10-05")
        val other = board("18:10:00", rt = false, tripId = "43@2026-10-05")
        assertEquals(mine, matchLegOnBoard(l, listOf(other, mine)))
    }

    @Test
    fun `a tracked bus puts its predicted time on the leg`() {
        val j = journey("18:00:00", "18:40:00", leg("18:10:00", "18:40:00", tripId = "42@2026-10-05"))
        val boards = mapOf("59772" to listOf(board("18:12:00", rt = true, tripId = "42@2026-10-05")))
        val ride = tagPhantomLegs(listOf(j), boards, now).single().sections.single() as JourneySection.Transit
        assertEquals(TimeSourceType.REALTIME, ride.timeSourceType)
        assertEquals(t("18:12:00"), ride.departureTime)
        assertEquals(t("18:42:00"), ride.arrivalTime)
        assertFalse(ride.isSuspectedPhantom)
    }

    // --- end to end: PlanItineraryUseCase ----------------------------------------------------------

    private class FakeItineraryRepository(private val responses: List<List<Journey>>) : ItineraryRepository {
        val requested = mutableListOf<Set<String>>()
        override suspend fun searchJourneys(
            fromLabel: String, fromLat: Double, fromLon: Double,
            toLabel: String, toLat: Double, toLon: Double,
            timePreference: ItineraryTimePreference,
            fromStopId: String?, toStopId: String?,
            excludedTripIds: Set<String>,
        ): DataResult<List<Journey>> {
            requested += excludedTripIds
            return DataResult.Success(responses.getOrElse(requested.size - 1) { emptyList() })
        }
    }

    /** Every stop shows [board]; a [provisional] board must never be used to tag phantoms. */
    private class FakeBoards(private val board: List<Departure>, private val provisional: Boolean = false) : RealtimeBoardRepository {
        override val updates: Flow<Unit> = emptyFlow()
        override suspend fun boards(stopIds: Collection<String>, priority: RealtimePriority) =
            stopIds.associateWith { StopBoard(board, provisional, DepartureSource.SIRI) }
        override suspend fun awaitRealtime(stopIds: Collection<String>, priority: RealtimePriority, timeoutMs: Long) =
            boards(stopIds, priority)
    }

    private suspend fun plan(repo: FakeItineraryRepository, board: List<Departure>, provisional: Boolean = false) =
        (PlanItineraryUseCase(repo, CheckJourneyPhantomsUseCase(FakeBoards(board, provisional)))(
            "A", 0.0, 0.0, "B", 0.0, 0.0, now = now,
        ) as DataResult.Success).data

    private val boardWithPhantom = listOf(
        board("18:05:00", rt = true),
        board("18:10:00", rt = false, phantom = true),
        board("18:17:00", rt = true),
    )

    @Test
    fun `a journey riding a phantom is replaced by a re-search excluding that bus`() = runTest {
        val phantomJourney = journey("18:00:00", "18:40:00", walk(300), leg("18:10:00", "18:40:00"))
        val clean = journey("18:00:00", "18:35:00", walk(300), leg("18:05:00", "18:35:00", source = TimeSourceType.REALTIME))
        val replacement = journey("18:12:00", "18:47:00", walk(300), leg("18:17:00", "18:47:00", source = TimeSourceType.REALTIME))
        val repo = FakeItineraryRepository(listOf(listOf(clean, phantomJourney), listOf(replacement, clean)))

        val result = plan(repo, boardWithPhantom)

        assertEquals(listOf(clean, replacement), result.journeys)
        assertEquals(1, result.avoidedPhantomJourneys)
        assertFalse(result.onlyPhantomJourneys)
        // The retry bans exactly the phantom run.
        assertEquals(listOf(emptySet(), setOf("trip-18:10:00")), repo.requested)
    }

    @Test
    fun `the stream shows the timetable journeys first, then the checked plan`() = runTest {
        val phantomJourney = journey("18:00:00", "18:40:00", walk(300), leg("18:10:00", "18:40:00"))
        val clean = journey("18:00:00", "18:35:00", walk(300), leg("18:05:00", "18:35:00"))
        val replacement = journey("18:12:00", "18:47:00", walk(300), leg("18:17:00", "18:47:00"))
        val repo = FakeItineraryRepository(listOf(listOf(phantomJourney, clean), listOf(replacement, clean)))
        val emissions = PlanItineraryUseCase(repo, CheckJourneyPhantomsUseCase(FakeBoards(boardWithPhantom)))
            .stream("A", 0.0, 0.0, "B", 0.0, 0.0, now = now)
            .toList()
            .map { (it as DataResult.Success).data }

        assertEquals(2, emissions.size)
        val (timetable, checked) = emissions
        assertTrue(timetable.realtimeCheckPending)
        // Unchecked, in departure order, the phantom bus still in.
        assertEquals(listOf(clean, phantomJourney), timetable.journeys)
        assertFalse(checked.realtimeCheckPending)
        assertEquals(1, checked.avoidedPhantomJourneys)
        assertTrue(checked.journeys.none { it.usesSuspectedPhantom })
        // The first search isn't repeated for the check: one search + one retry.
        assertEquals(2, repo.requested.size)
    }

    @Test
    fun `a search far from now emits once, with nothing pending`() = runTest {
        val later = journey("20:00:00", "20:30:00", walk(300), leg("20:05:00", "20:30:00"))
        val repo = FakeItineraryRepository(listOf(listOf(later)))
        val emissions = PlanItineraryUseCase(repo, CheckJourneyPhantomsUseCase(FakeBoards(boardWithPhantom)))
            .stream("A", 0.0, 0.0, "B", 0.0, 0.0, now = now)
            .toList()
        assertEquals(1, emissions.size)
        assertFalse((emissions.single() as DataResult.Success).data.realtimeCheckPending)
    }

    @Test
    fun `when every option rides a phantom they are kept, tagged, rather than leaving nothing`() = runTest {
        val phantomJourney = journey("18:00:00", "18:40:00", walk(300), leg("18:10:00", "18:40:00"))
        val repo = FakeItineraryRepository(listOf(listOf(phantomJourney), listOf(phantomJourney), listOf(phantomJourney)))

        val result = plan(repo, boardWithPhantom)

        assertTrue(result.onlyPhantomJourneys)
        assertTrue(result.journeys.single().usesSuspectedPhantom)
        // The re-search found the same phantom run again (nothing new to exclude): stop there.
        assertEquals(2, repo.requested.size)
    }

    @Test
    fun `a provisional board never tags phantoms`() = runTest {
        val phantomJourney = journey("18:00:00", "18:40:00", walk(300), leg("18:10:00", "18:40:00"))
        val repo = FakeItineraryRepository(listOf(listOf(phantomJourney)))
        val result = plan(repo, boardWithPhantom, provisional = true)
        assertEquals(listOf(phantomJourney), result.journeys)
        assertEquals(1, repo.requested.size)
    }

    @Test
    fun `no phantom, no extra search`() = runTest {
        val clean = journey("18:00:00", "18:35:00", walk(300), leg("18:05:00", "18:35:00", source = TimeSourceType.REALTIME))
        val repo = FakeItineraryRepository(listOf(listOf(clean)))
        val result = plan(repo, boardWithPhantom)
        assertEquals(listOf(clean), result.journeys)
        assertEquals(0, result.avoidedPhantomJourneys)
        assertEquals(1, repo.requested.size)
    }
}

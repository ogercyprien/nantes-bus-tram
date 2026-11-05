package com.bustramnantes.app.data.realtime

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.data.remote.okina.RateLimitedException
import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.DepartureSource
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.repository.RealtimePriority
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.BacsDeLoire
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.gtfs.appending
import com.bustramnantes.transit.realtime.SiriVisit
import com.bustramnantes.transit.realtime.StopTimePrediction
import com.bustramnantes.transit.realtime.TripUpdate
import com.bustramnantes.transit.realtime.TripUpdatesFeed
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RealtimeGateway] on the `:transit` mini GTFS fixture, with fake Okina sources and virtual time
 * (Tuesday 2026-10-13 07:55 Paris = t0).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeGatewayTest {

    private val raw = GtfsConverter(GtfsSource.directory(File("../transit/src/test/resources/gtfs-mini"))).convert()
    private val index = NetworkIndex(raw)
    private val zone = ZoneId.of("Europe/Paris")
    private val t0 = LocalDateTime.of(2026, 10, 13, 7, 55).atZone(zone).toEpochSecond() * 1000
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 13, h, m, s).atZone(zone).toEpochSecond()

    private val alpha = "FR_NAOLIB:StopPlace:1"
    private val beta = "FR_NAOLIB:StopPlace:2"

    private class FakeSources(private val now: () -> Long) : RealtimeSources {
        val siriCalls = mutableListOf<Pair<Long, Collection<String>>>()
        var siriVisits: List<SiriVisit> = emptyList()
        var siriFailure: Exception? = null
        var tripUpdates: TripUpdatesFeed? = null

        val bulkCalls = mutableListOf<Collection<String>>()
        override suspend fun stopMonitoring(quayIds: Collection<String>, bulk: Boolean): List<SiriVisit> {
            if (bulk) bulkCalls += quayIds
            siriCalls += now() to quayIds
            siriFailure?.let { throw it }
            return siriVisits.filter { it.monitoringRef in quayIds }
        }

        override suspend fun tripUpdates(): TripUpdatesFeed = tripUpdates ?: throw java.io.IOException("offline")
    }

    /** A persisted "last SIRI request" shared by every gateway built on it (= the DataStore). */
    private class Store { var lastSiri = 0L }

    private fun TestScope.gateway(sources: FakeSources, store: Store = Store(), idx: NetworkIndex = index): RealtimeGateway {
        val clock = { t0 + testScheduler.currentTime }
        var lastRt = 0L
        return RealtimeGateway(
            indexSource = { idx },
            sources = sources,
            siriBudget = RequestBudget(RealtimeGateway.SIRI_MIN_INTERVAL_MS, { store.lastSiri }, { store.lastSiri = it }, clock),
            gtfsRtBudget = RequestBudget(RealtimeGateway.GTFS_RT_MIN_INTERVAL_MS, { lastRt }, { lastRt = it }, clock),
            scope = backgroundScope,
            clock = clock,
        )
    }

    private fun TestScope.sources() = FakeSources { t0 + testScheduler.currentTime }

    private fun visit(vj: String, quay: String, time: Long, dest: String = "FR_NAOLIB:Quay:31") =
        SiriVisit(quay, "FR_NAOLIB:Line:T1", vj, "A", dest, "Gamma", time, time, time, time, true)

    @Test
    fun `cache miss gives a provisional GTFS-RT board, replaced by SIRI at the next slot`() = runTest {
        val sources = sources().apply {
            tripUpdates = TripUpdatesFeed(
                at(7, 55),
                listOf(
                    TripUpdate(
                        "FR_NAOLIB:VehicleJourney:100-WEEK",
                        listOf(StopTimePrediction(1, "FR_NAOLIB:Quay:11", null, null, at(8, 1), 60)),
                    ),
                ),
            )
            siriVisits = listOf(visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:11", at(8, 1, 30)))
        }
        val gw = gateway(sources)

        val first = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertTrue(first.provisional)
        assertEquals(DepartureSource.GTFS_RT, first.source)
        assertEquals(Instant.fromEpochSeconds(at(8, 1)), first.departures.first().nextDepartedTime)

        runCurrent() // the scheduler's first slot is free -> one SIRI request
        assertEquals(1, sources.siriCalls.size)
        val second = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertFalse(second.provisional)
        assertEquals(DepartureSource.SIRI, second.source)
        assertEquals(Instant.fromEpochSeconds(at(8, 1, 30)), second.departures.first().nextDepartedTime)
        assertTrue(second.departures.first().isRealtime)
    }

    @Test
    fun `a bac landing is a timetable board and never asked to SIRI`() = runTest {
        val withBacs = NetworkIndex(
            GtfsConverter(
                GtfsSource.directory(File("../transit/src/test/resources/gtfs-mini"))
                    .appending(GtfsSource.directory(File("../transit/src/test/resources/bacs-loire")), BacsDeLoire.SPEC),
            ).convert(),
        )
        val indret = withBacs.stops.single { it.name == "Indret" }.id
        val sources = sources()
        val gw = gateway(sources, idx = withBacs)
        val board = gw.awaitRealtime(listOf(indret), RealtimePriority.OPEN_STOP, timeoutMs = 5_000).getValue(indret)
        assertFalse(board.provisional)
        assertEquals(DepartureSource.TIMETABLE, board.source)
        assertEquals("B1", board.departures.first().lineNumber)
        assertEquals(TransportMode.BAC, board.departures.first().transportMode)
        runCurrent()
        assertTrue(sources.siriCalls.isEmpty())
        assertEquals(0L, testScheduler.currentTime) // no waiting for a SIRI slot
    }

    @Test
    fun `the network prefetch fills every quay in one request, then serves cached real time`() = runTest {
        val sources = sources().apply {
            siriVisits = listOf(visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:11", at(8, 1, 30)))
        }
        val gw = gateway(sources)
        gw.prefetchNetwork()
        runCurrent()
        assertEquals(1, sources.bulkCalls.size)
        assertEquals(index.network.quayCount, sources.bulkCalls.single().size)

        // Right after: fresh SIRI, no request needed for the stop.
        val fresh = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertEquals(DepartureSource.SIRI, fresh.source)
        assertFalse(fresh.stale)
        assertTrue(fresh.departures.first().isRealtime)

        // A second foreground within 10 min: no new bulk request.
        gw.prefetchNetwork()
        runCurrent()
        assertEquals(1, sources.bulkCalls.size)
    }

    @Test
    fun `a cached snapshot a few minutes old is shown at once, refreshed at the next slot`() = runTest {
        val sources = sources().apply {
            siriVisits = listOf(visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:11", at(8, 1, 30)))
        }
        val gw = gateway(sources)
        gw.prefetchNetwork()
        runCurrent()
        testScheduler.advanceTimeBy(2 * 60_000L) // the cached data is now 2 min old

        val cached = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertEquals(DepartureSource.SIRI, cached.source)
        assertTrue(cached.stale)
        assertTrue(cached.realtimePending)
        assertFalse(cached.provisional)
        assertTrue(cached.departures.none { it.isSuspectedPhantom }) // never tagged from old data
        assertEquals(t0, cached.realtimeFetchedAtMs)

        runCurrent() // the stop's interest -> one targeted request in the free slot
        assertEquals(2, sources.siriCalls.size)
        val refreshed = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertFalse(refreshed.stale)
    }

    @Test
    fun `past five minutes the cache is no longer shown`() = runTest {
        val sources = sources()
        val gw = gateway(sources)
        gw.prefetchNetwork()
        runCurrent()
        sources.siriFailure = java.io.IOException("offline")
        testScheduler.advanceTimeBy(RealtimeGateway.STALE_MAX_MS + 1_000)
        val board = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertTrue(board.provisional)
        assertTrue(board.realtimePending) // nothing has failed yet: a request is coming
    }

    @Test
    fun `one batched request carries every wanted stop's quays`() = runTest {
        val sources = sources()
        val gw = gateway(sources)
        gw.boards(listOf(alpha, beta), RealtimePriority.FAVORITES)
        runCurrent()
        assertEquals(1, sources.siriCalls.size)
        assertEquals(
            setOf("FR_NAOLIB:Quay:11", "FR_NAOLIB:Quay:12", "FR_NAOLIB:Quay:21", "FR_NAOLIB:Quay:22"),
            sources.siriCalls.single().second.toSet(),
        )
    }

    @Test
    fun `never two SIRI requests less than 31 s apart, even across processes`() = runTest {
        val sources = sources()
        val store = Store()
        val app = gateway(sources, store)
        val widgetProcess = gateway(sources, store)
        app.boards(listOf(alpha), RealtimePriority.OPEN_STOP)
        runCurrent()
        advanceTimeBy(5_000)
        widgetProcess.boards(listOf(beta), RealtimePriority.FAVORITES)
        // Keep both interested for a few minutes.
        repeat(6) {
            advanceTimeBy(30_000)
            app.boards(listOf(alpha), RealtimePriority.OPEN_STOP)
            widgetProcess.boards(listOf(beta), RealtimePriority.FAVORITES)
        }
        val times = sources.siriCalls.map { it.first }.sorted()
        assertTrue("several requests made: ${times.size}", times.size >= 4)
        times.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a} ms", b - a >= RealtimeGateway.SIRI_MIN_INTERVAL_MS) }
    }

    @Test
    fun `a 429 leaves the timetable, says real time is unavailable, then waits a full slot`() = runTest {
        val sources = sources().apply { siriFailure = RateLimitedException() }
        val gw = gateway(sources)
        gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP)
        runCurrent()
        val board = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertTrue(board.provisional)
        assertFalse(board.realtimePending)
        assertTrue(board.departures.none { it.isRealtime })
        // No immediate retry after the 429.
        advanceTimeBy(20_000)
        assertEquals(1, sources.siriCalls.size)
        advanceTimeBy(12_000)
        assertEquals(2, sources.siriCalls.size)
    }

    @Test
    fun `phantoms are tagged on SIRI boards, never on provisional ones`() = runTest {
        // At 07:55 the imminent timetabled T1 run is 100 (08:00). SIRI lists only the later run 101
        // (08:30), so 100 is theoretical, imminent, on a line with real-time data: a phantom.
        val sources = sources().apply {
            siriVisits = listOf(visit("FR_NAOLIB:VehicleJourney:101-WEEK", "FR_NAOLIB:Quay:11", at(8, 30)))
            tripUpdates = TripUpdatesFeed(at(7, 55), emptyList())
        }
        val gw = gateway(sources)
        val provisional = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertTrue(provisional.provisional)
        assertTrue(provisional.departures.none { it.isSuspectedPhantom })

        runCurrent()
        val siri = gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP).getValue(alpha)
        assertEquals(DepartureSource.SIRI, siri.source)
        val imminent = siri.departures.first { it.nextDepartedTime == Instant.fromEpochSeconds(at(8, 0)) }
        assertFalse(imminent.isRealtime)
        // 101 is 35 min away: listed by SIRI and within 50 min -> real-time, so the line has RT data.
        assertTrue(siri.departures.any { it.isRealtime })
        assertTrue(imminent.isSuspectedPhantom)
    }

    @Test
    fun `the poller stops once nothing is watched`() = runTest {
        val sources = sources()
        val gw = gateway(sources)
        gw.boards(listOf(alpha), RealtimePriority.OPEN_STOP)
        advanceTimeBy(5 * 60_000)
        val calls = sources.siriCalls.size
        assertTrue("interest lapses after ~75 s: $calls calls", calls in 2..4)
        advanceTimeBy(10 * 60_000)
        assertEquals(calls, sources.siriCalls.size)
    }

    @Test
    fun `awaitRealtime waits for the slot covering the stops`() = runTest {
        val sources = sources().apply {
            siriVisits = listOf(visit("FR_NAOLIB:VehicleJourney:100-WEEK", "FR_NAOLIB:Quay:11", at(8, 0)))
        }
        val store = Store().apply { lastSiri = t0 - 10_000 } // budget spent 10 s ago
        val gw = gateway(sources, store)
        val boards = gw.awaitRealtime(listOf(alpha), RealtimePriority.FAVORITES, timeoutMs = 40_000)
        assertEquals(DepartureSource.SIRI, boards.getValue(alpha).source)
        assertEquals(t0 + 21_000, sources.siriCalls.single().first)
    }
}

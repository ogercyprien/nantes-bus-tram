package com.bustramnantes.app.data.realtime

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.domain.model.DisruptionCause
import com.bustramnantes.app.domain.model.DisruptionTarget
import com.bustramnantes.transit.NetworkFormat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GtfsRtDisruptionRepository] on the bundled seed network and the Okina Alerts feed as captured
 * in the `:transit` test resources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GtfsRtDisruptionRepositoryTest {

    private val index = NetworkIndex(File("src/main/assets/seed/network.bin").inputStream().use(NetworkFormat::read))
    private val feed = File("../transit/src/test/resources/okina/alerts.pb").readBytes()

    private class MemoryStore(var stored: Pair<ByteArray, Long>? = null) : AlertsStore {
        var saves = 0
        override fun load() = stored
        override fun save(bytes: ByteArray, fetchedAt: Long) { stored = bytes to fetchedAt; saves++ }
    }

    private fun TestScope.repository(store: AlertsStore, fetch: suspend () -> ByteArray, clock: () -> Long): GtfsRtDisruptionRepository {
        var last = 0L
        return GtfsRtDisruptionRepository(
            indexSource = { index },
            fetch = fetch,
            budget = RequestBudget(31_000, { last }, { last = it }, clock),
            store = store,
            scope = backgroundScope,
            clock = clock,
        )
    }

    @Test
    fun `maps the feed onto the network - lines, quays to StopPlaces, inferred lines, titles`() {
        val disruptions = GtfsRtDisruptionRepository.toDisruptions(
            com.bustramnantes.transit.realtime.ServiceAlerts.decode(feed).alerts,
            index,
        )
        assertEquals(32, disruptions.size)
        assertTrue(disruptions.all { it.title.isNotBlank() })
        val bonde = disruptions.first { "Bonde" in it.title }
        val bondeStop = index.network.stopPlaces[index.network.quayStopPlace[index.network.quayIndex("FR_NAOLIB:Quay:2497")!!]].id
        assertTrue(DisruptionTarget("FR_NAOLIB:Line:C6", bondeStop) in bonde.targets)
        // No entity in the feed; the text names line C6.
        assertEquals(setOf("FR_NAOLIB:Line:C6"), disruptions.first { it.title == "Travaux rue Sully" }.lineIds)
        assertEquals(DisruptionCause.DEMONSTRATION, disruptions.first { it.title.startsWith("Manifestation du mardi") }.cause)
    }

    @Test
    fun `stored feed shows at once, refresh waits for the interval and persists`() = runTest {
        val clock = { 1_000_000_000L + testScheduler.currentTime }
        val store = MemoryStore(feed to clock() - 60_000)
        var fetches = 0
        val repo = repository(store, { fetches++; feed }, clock)
        runCurrent()
        assertEquals(32, repo.disruptions.value.size)

        repo.refreshIfStale() // stored copy is 1 min old: no fetch
        assertEquals(0, fetches)

        testScheduler.advanceTimeBy(GtfsRtDisruptionRepository.REFRESH_INTERVAL_MS)
        repo.refreshIfStale()
        assertEquals(1, fetches)
        assertEquals(1, store.saves)
        repo.refreshIfStale()
        assertEquals(1, fetches)
    }

    @Test
    fun `a failed fetch keeps the last notices and is retried a minute later`() = runTest {
        val clock = { 2_000_000_000L + testScheduler.currentTime }
        var fail = true
        var fetches = 0
        val repo = repository(MemoryStore(), { fetches++; if (fail) throw java.io.IOException("offline") else feed }, clock)
        repo.refreshIfStale()
        assertEquals(1, fetches)
        assertTrue(repo.disruptions.value.isEmpty())
        repo.refreshIfStale()
        assertEquals(1, fetches)
        fail = false
        testScheduler.advanceTimeBy(GtfsRtDisruptionRepository.RETRY_INTERVAL_MS)
        repo.refreshIfStale()
        assertEquals(2, fetches)
        assertEquals(32, repo.disruptions.value.size)
    }

    @Test
    fun `title falls back to the description's first words`() {
        assertEquals("Perturbation", GtfsRtDisruptionRepository.titleFromDescription("<p>&nbsp;</p>"))
        val long = "<h4>Du lundi 31 août</h4><p>Travaux de voirie quais Langlois et Jean Bart, les lignes 50 et 81 sont déviées</p>"
        val title = GtfsRtDisruptionRepository.titleFromDescription(long)
        assertTrue(title.startsWith("Du lundi 31 août Travaux de voirie"))
        assertTrue(title.endsWith("…") && title.length <= 71)
    }
}

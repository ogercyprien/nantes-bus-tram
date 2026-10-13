package com.bustramnantes.app.data.realtime

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.data.network.boardComposer
import com.bustramnantes.app.data.network.toDeparture
import com.bustramnantes.app.domain.model.DepartureSource
import com.bustramnantes.app.domain.model.StopBoard
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import com.bustramnantes.app.domain.util.tagSuspectedPhantoms
import com.bustramnantes.transit.realtime.SiriVisit
import com.bustramnantes.transit.realtime.TripUpdate
import com.bustramnantes.transit.realtime.TripUpdatesFeed
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/** Something that hands out the current network index (the real one is `TransitNetworkRepository`). */
fun interface NetworkIndexSource {
    suspend fun index(): NetworkIndex
}

/** The upstream real-time calls (real: Okina SIRI + GTFS-RT), behind an interface for tests. */
interface RealtimeSources {
    /** [bulk]: the whole network in one request (slow, ~1.2 MB) - see [RealtimeGateway.prefetchNetwork]. */
    suspend fun stopMonitoring(quayIds: Collection<String>, bulk: Boolean = false): List<SiriVisit>
    suspend fun tripUpdates(): TripUpdatesFeed
}

/**
 * The single owner of real-time departure data. Every surface - Stop Detail, Home
 * favorites, the widget, the itinerary phantom check - asks it for boards; it turns all of that
 * interest into **one batched Okina SIRI StopMonitoring request per slot**, never two requests less
 * than [siriBudget]'s interval (31 s) apart, app-wide and across processes (persisted timestamp).
 *
 * - **Interest registry**: [boards]/[awaitRealtime] register the stops' quays with a priority; an
 *   interest lapses [INTEREST_TTL_MS] after the last ask (pollers re-ask every ≤ 30 s). Each slot
 *   requests the wanted quays by priority (open stop > itinerary > favorites > prefetch), capped at
 *   [MAX_QUAYS_PER_REQUEST]. When nothing is wanted the loop stops: no background polling.
 * - **Per-quay cache**: a quay's visits are "fresh" for [FRESH_MS] (two slots), and still shown as
 *   cached real time up to [STALE_MAX_MS] while a refresh is pending.
 * - **Network prefetch** ([prefetchNetwork]): when the app comes to the foreground, every quay in one
 *   request, so a stop opened next shows real time immediately.
 * - **Board for a stop**, best first:
 *   1. every quay fresh -> SIRI board ([com.bustramnantes.transit.realtime.BoardComposer.composeWithSiri])
 *      + phantom tagging;
 *   2. a cached SIRI snapshot ≤ [STALE_MAX_MS] old, shown at once while a refresh is pending;
 *   3. otherwise **provisional**: the timetable with a GTFS-RT TripUpdates overlay (snapshot reused for
 *      31 s, fetched on its own budget when free) or the bare timetable - no phantom tagging. The stop
 *      is queued for the next SIRI slot and [updates] fires when it lands.
 *
 * Failures never surface as errors: the worst case is the local timetable.
 */
class RealtimeGateway(
    private val indexSource: NetworkIndexSource,
    private val sources: RealtimeSources,
    private val siriBudget: RequestBudget,
    private val gtfsRtBudget: RequestBudget,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : RealtimeBoardRepository {

    private class Interest(val priority: RealtimePriority, val askedAt: Long)
    private class QuaySnapshot(val fetchedAt: Long, val visits: List<SiriVisit>)
    private class TripUpdatesSnapshot(val fetchedAt: Long, val updates: Map<String, TripUpdate>)

    private val interests = ConcurrentHashMap<String, Interest>()
    private val quayCache = ConcurrentHashMap<String, QuaySnapshot>()
    @Volatile private var tripUpdates: TripUpdatesSnapshot? = null
    @Volatile private var lastSiriSuccess = 0L
    @Volatile private var lastSiriFailure = 0L
    @Volatile private var lastNetworkFetch = 0L
    @Volatile private var lastNetworkAttempt = 0L
    private val networkPrefetchRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private val schedulerLock = Mutex()
    private var scheduler: Job? = null
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    /** Quays sent in the last SIRI requests (debug/tests). */
    @Volatile var lastRequestedQuays: List<String> = emptyList()
        private set

    override suspend fun boards(stopIds: Collection<String>, priority: RealtimePriority): Map<String, StopBoard> {
        val index = indexSource.index()
        register(index, stopIds, priority)
        return stopIds.distinct().associateWith { board(index, it) }
    }

    override suspend fun awaitRealtime(
        stopIds: Collection<String>,
        priority: RealtimePriority,
        timeoutMs: Long,
    ): Map<String, StopBoard> {
        val index = indexSource.index()
        register(index, stopIds, priority)
        withTimeoutOrNull(timeoutMs) {
            while (!stopIds.all { allFresh(index, it) || siriQuays(index, it).isEmpty() }) {
                updates.first()
            }
        }
        return stopIds.distinct().associateWith { board(index, it) }
    }

    private suspend fun board(index: NetworkIndex, stopId: String): StopBoard {
        val quays = index.quays(stopId)
        if (quays.isEmpty()) return StopBoard(emptyList(), provisional = false, source = DepartureSource.TIMETABLE)
        val now = clock()
        val nowSec = now / 1000
        val composer = index.boardComposer()
        // A stop served only by the bacs (no Naolib quay): SIRI has nothing - the timetable is the board.
        if (siriQuays(index, stopId).isEmpty()) {
            val departures = composer.composeTimetable(quays, nowSec).map(index::toDeparture)
            return StopBoard(departures, provisional = false, source = DepartureSource.TIMETABLE)
        }
        val quayIds = siriQuays(index, stopId).map { index.network.quayId(it) }
        val snapshots = quayIds.map { quayCache[it] }
        val oldest = if (snapshots.all { it != null }) snapshots.minOf { it!!.fetchedAt } else null
        val failedRecently = lastSiriFailure > lastSiriSuccess && now - lastSiriFailure <= FAILURE_WINDOW_MS
        // 1. Fresh SIRI (≤ 65 s, i.e. this or the previous slot): the real board, phantom-tagged.
        if (oldest != null && now - oldest <= FRESH_MS) {
            val visits = snapshots.flatMap { it!!.visits }
            val departures = composer.composeWithSiri(quays, visits, nowSec).map(index::toDeparture)
                .tagSuspectedPhantoms(Instant.fromEpochMilliseconds(now))
            return StopBoard(departures, provisional = false, source = DepartureSource.SIRI, realtimeFetchedAtMs = oldest)
        }
        // 2. A cached SIRI snapshot a few minutes old (the network prefetch, an earlier visit): shown at
        //    once while this stop waits for the next slot. Expected times are still the best estimate;
        //    no phantom tagging (a bus untracked then may be tracked now).
        if (oldest != null && now - oldest <= STALE_MAX_MS) {
            val visits = snapshots.flatMap { it!!.visits }
            val departures = composer.composeWithSiri(quays, visits, nowSec).map(index::toDeparture)
            return StopBoard(
                departures, provisional = false, source = DepartureSource.SIRI,
                realtimeFetchedAtMs = oldest, stale = true, realtimePending = !failedRecently,
            )
        }
        // 3. Provisional: GTFS-RT overlay or the bare timetable, until SIRI lands.
        val updates = currentTripUpdates(now)
        return if (updates != null) {
            val departures = composer.composeWithTripUpdates(quays, updates, nowSec).map(index::toDeparture)
            StopBoard(departures, provisional = true, source = DepartureSource.GTFS_RT, realtimePending = !failedRecently)
        } else {
            val departures = composer.composeTimetable(quays, nowSec).map(index::toDeparture)
            StopBoard(departures, provisional = true, source = DepartureSource.TIMETABLE, realtimePending = !failedRecently)
        }
    }

    /**
     * Fetches the real-time data of **every Naolib quay in one SIRI request** (~2,500 quays: 1.2 MB
     * gzipped, 8-10 s server time - SOURCES.md §5.2), so any stop opened in the next minutes shows real
     * time at once ([STALE_MAX_MS]) instead of the timetable. Called when the app comes to the
     * foreground; at most once per [NETWORK_PREFETCH_INTERVAL_MS] (after a failure, retried after
     * [NETWORK_PREFETCH_RETRY_MS]). Uses an ordinary slot of the SIRI budget - targeted requests resume
     * at the next one. Never refreshed in a loop: 1.2 MB every 30 s would cost ~150 MB an hour.
     */
    override fun prefetchNetwork() {
        val now = clock()
        if (now - lastNetworkFetch < NETWORK_PREFETCH_INTERVAL_MS || now - lastNetworkAttempt < NETWORK_PREFETCH_RETRY_MS) return
        if (!networkPrefetchRunning.compareAndSet(false, true)) return
        lastNetworkAttempt = now
        scope.launch {
            try {
                val index = indexSource.index()
                val network = index.network
                val quays = (0 until network.quayCount).filterNot(network::isExternalQuay).map(network::quayId)
                val done = siriBudget.withSlot {
                    try {
                        val visits = sources.stopMonitoring(quays, bulk = true)
                        val fetchedAt = clock()
                        val byQuay = visits.groupBy { it.monitoringRef }
                        for (q in quays) quayCache[q] = QuaySnapshot(fetchedAt, byQuay[q].orEmpty())
                        lastSiriSuccess = fetchedAt
                        lastNetworkFetch = fetchedAt
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastSiriFailure = clock()
                    }
                }
                if (done != null) _updates.emit(Unit)
            } finally {
                networkPrefetchRunning.set(false)
            }
        }
    }

    /** The GTFS-RT snapshot if < [TRIP_UPDATES_REUSE_MS] old, else a fresh one when its budget allows. */
    private suspend fun currentTripUpdates(now: Long): Map<String, TripUpdate>? {
        tripUpdates?.takeIf { now - it.fetchedAt <= TRIP_UPDATES_REUSE_MS }?.let { return it.updates }
        val fetched = runCatching {
            gtfsRtBudget.tryWithSlot { sources.tripUpdates() }
        }.getOrNull()
        if (fetched != null) {
            val snapshot = TripUpdatesSnapshot(clock(), fetched.updates.associateBy { it.tripId })
            tripUpdates = snapshot
            return snapshot.updates
        }
        // Budget spent / failed: an older snapshot is still better than nothing for a minute or two.
        return tripUpdates?.takeIf { now - it.fetchedAt <= TRIP_UPDATES_MAX_AGE_MS }?.updates
    }

    /** The stop's quays SIRI knows: Naolib's (the bacs' landings are timetable-only). */
    private fun siriQuays(index: NetworkIndex, stopId: String): List<Int> =
        index.quays(stopId).filterNot(index.network::isExternalQuay)

    private fun allFresh(index: NetworkIndex, stopId: String): Boolean {
        val now = clock()
        return siriQuays(index, stopId).all { q ->
            quayCache[index.network.quayId(q)]?.let { now - it.fetchedAt <= FRESH_MS } == true
        }
    }

    /**
     * Records interest in [stopIds]. A stop SIRI can't cover (a bac landing only) is never registered:
     * nothing would ever be requested for it, and an interest that can't be served kept the scheduler
     * spinning without a slot to wait for.
     */
    private suspend fun register(index: NetworkIndex, stopIds: Collection<String>, priority: RealtimePriority) {
        val now = clock()
        for (id in stopIds) {
            if (siriQuays(index, id).isEmpty()) continue
            val existing = interests[id]
            val p = if (existing != null && now - existing.askedAt <= INTEREST_TTL_MS && existing.priority > priority) existing.priority else priority
            interests[id] = Interest(p, now)
        }
        ensureScheduler()
    }

    private suspend fun ensureScheduler() = schedulerLock.withLock {
        if (scheduler?.isActive == true) return@withLock
        scheduler = scope.launch { runScheduler() }
    }

    private suspend fun runScheduler() {
        while (true) {
            val index = indexSource.index()
            var quays: List<String> = emptyList()
            val requested = siriBudget.withSlot(
                precondition = {
                    val now = clock()
                    interests.entries.removeIf { now - it.value.askedAt > INTEREST_TTL_MS }
                    quays = if (interests.isEmpty()) emptyList() else selectQuays(index, now)
                    quays.isNotEmpty()
                },
            ) {
                lastRequestedQuays = quays
                try {
                    val visits = sources.stopMonitoring(quays)
                    val fetchedAt = clock()
                    val byQuay = visits.groupBy { it.monitoringRef }
                    for (q in quays) quayCache[q] = QuaySnapshot(fetchedAt, byQuay[q].orEmpty())
                    lastSiriSuccess = fetchedAt
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastSiriFailure = clock()
                }
            }
            if (requested == null) {
                // Nothing wanted. Re-check under the lock so a concurrent register() can't be lost.
                // Also stop when the remaining interests can't fill a request (no SIRI quay at all):
                // looping on them would spin, since an unused slot doesn't wait.
                val stop = schedulerLock.withLock {
                    val now = clock()
                    interests.entries.removeIf { now - it.value.askedAt > INTEREST_TTL_MS }
                    if (interests.isEmpty() || interests.keys.all { siriQuays(index, it).isEmpty() }) {
                        scheduler = null
                        true
                    } else {
                        false
                    }
                }
                if (stop) return
            } else {
                _updates.emit(Unit)
            }
        }
    }

    /** Wanted quays, highest priority (then most recently asked) first, capped. */
    private fun selectQuays(index: NetworkIndex, now: Long): List<String> {
        val ordered = interests.entries.sortedWith(
            compareByDescending<Map.Entry<String, Interest>> { it.value.priority }.thenByDescending { it.value.askedAt },
        )
        val out = LinkedHashSet<String>()
        for ((stopId, _) in ordered) {
            val quays = siriQuays(index, stopId).map { index.network.quayId(it) }
            if (out.size + quays.count { it !in out } > MAX_QUAYS_PER_REQUEST && out.isNotEmpty()) continue
            out += quays
        }
        // Drop entries that can no longer matter (old snapshots) to bound memory.
        quayCache.entries.removeIf { now - it.value.fetchedAt > CACHE_MAX_AGE_MS }
        return out.toList()
    }

    companion object {
        /** SIRI budget interval: Okina's 30 s plus a second of margin. */
        const val SIRI_MIN_INTERVAL_MS = 31_000L
        const val GTFS_RT_MIN_INTERVAL_MS = 31_000L
        const val FRESH_MS = 65_000L
        /** A cached SIRI snapshot is still shown (as "temps réel de HH:mm") up to this age. */
        const val STALE_MAX_MS = 5 * 60_000L
        const val NETWORK_PREFETCH_INTERVAL_MS = 10 * 60_000L
        const val NETWORK_PREFETCH_RETRY_MS = 2 * 60_000L
        const val INTEREST_TTL_MS = 75_000L
        const val MAX_QUAYS_PER_REQUEST = 120
        /** After an Okina failure, boards say real time is unavailable (not "on its way") for this long. */
        const val FAILURE_WINDOW_MS = 90_000L
        private const val TRIP_UPDATES_REUSE_MS = 31_000L
        private const val TRIP_UPDATES_MAX_AGE_MS = 120_000L
        private const val CACHE_MAX_AGE_MS = 10 * 60_000L
    }
}

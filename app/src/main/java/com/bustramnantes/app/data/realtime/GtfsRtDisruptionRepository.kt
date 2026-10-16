package com.bustramnantes.app.data.realtime

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.DisruptionCause
import com.bustramnantes.app.domain.model.DisruptionPeriod
import com.bustramnantes.app.domain.model.DisruptionTarget
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.realtime.ServiceAlert
import com.bustramnantes.transit.realtime.ServiceAlerts
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/** Where the last raw Alerts message lives between runs (the app's files dir in production). */
interface AlertsStore {
    /** The stored message and when it was fetched (epoch ms), or null. */
    fun load(): Pair<ByteArray, Long>?
    fun save(bytes: ByteArray, fetchedAt: Long)
}

/** [AlertsStore] over a file; the fetch time is the file's modification time. */
class FileAlertsStore(private val file: File) : AlertsStore {
    override fun load(): Pair<ByteArray, Long>? =
        runCatching { if (file.isFile) file.readBytes() to file.lastModified() else null }.getOrNull()

    override fun save(bytes: ByteArray, fetchedAt: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.setLastModified(fetchedAt)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setLastModified(fetchedAt)
        }
    }
}

/**
 * [DisruptionRepository] over the Okina **GTFS-RT Alerts** feed (keyless, whole network in ~12 KB).
 *
 * - **Budget**: GTFS-RT allows 1 request / 30 s per IP, shared with the TripUpdates fetched for
 *   provisional boards - so this goes through the same [budget] ([RequestBudget.withSlot]: waits for
 *   the slot rather than failing). Notices change slowly: a fetch at most every [REFRESH_INTERVAL_MS]
 *   while a screen asks, nothing in the background.
 * - **Persistence**: the raw message goes to [store], so the last known notices show offline and on
 *   the next launch, re-mapped against whatever network is installed then.
 * - **Mapping**: `ServiceAlerts.clean` folds the feed's blank twin records into the titled notices and
 *   infers the lines an untargeted notice names; quay targets become their StopPlace ids, lines the
 *   network doesn't know are dropped.
 */
class GtfsRtDisruptionRepository(
    private val indexSource: NetworkIndexSource,
    private val fetch: suspend () -> ByteArray,
    private val budget: RequestBudget,
    private val store: AlertsStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : DisruptionRepository {

    private val _disruptions = MutableStateFlow<List<Disruption>>(emptyList())
    override val disruptions: StateFlow<List<Disruption>> = _disruptions.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var lastFetchAt = 0L
    @Volatile private var lastAttemptAt = 0L

    init {
        scope.launch {
            val stored = store.load() ?: return@launch
            mutex.withLock {
                if (lastFetchAt == 0L) {
                    lastFetchAt = stored.second
                    publish(stored.first)
                }
            }
        }
    }

    override suspend fun refreshIfStale() {
        mutex.withLock {
            val now = clock()
            if (now - lastFetchAt < REFRESH_INTERVAL_MS || now - lastAttemptAt < RETRY_INTERVAL_MS) return
            lastAttemptAt = now
            try {
                val bytes = budget.withSlot { fetch() } ?: return
                val fetchedAt = clock()
                publish(bytes)
                lastFetchAt = fetchedAt
                store.save(bytes, fetchedAt)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Keep the last known notices; retried after RETRY_INTERVAL_MS.
            }
        }
    }

    private suspend fun publish(bytes: ByteArray) {
        val index = indexSource.index()
        val feed = runCatching { ServiceAlerts.decode(bytes) }.getOrNull() ?: return
        _disruptions.value = toDisruptions(feed.alerts, index)
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60_000L
        const val RETRY_INTERVAL_MS = 60_000L

        /** Cleans the feed and maps it onto [index] (see the class doc). */
        fun toDisruptions(alerts: List<ServiceAlert>, index: NetworkIndex): List<Disruption> {
            val network = index.network
            val knownLines = index.lines.associate { it.id to it.shortName }
            val cleaned = ServiceAlerts.clean(alerts) { short -> index.line(short)?.id }
            return cleaned.map { alert ->
                val targets = alert.entities.mapNotNull { entity ->
                    val lineId = entity.routeId?.takeIf { it in knownLines }
                    val stopId = entity.stopId?.let { stopPlaceIdOfQuay(network, it) }
                    if (lineId == null && stopId == null) null else DisruptionTarget(lineId, stopId)
                }.distinct()
                Disruption(
                    id = alert.id,
                    title = alert.header.ifBlank { titleFromDescription(alert.description) },
                    descriptionHtml = alert.description,
                    url = alert.url,
                    periods = alert.periods.map { p ->
                        DisruptionPeriod(p.start?.let(Instant::fromEpochSeconds), p.end?.let(Instant::fromEpochSeconds))
                    },
                    targets = targets,
                    cause = when (alert.cause) {
                        10 -> DisruptionCause.CONSTRUCTION
                        5 -> DisruptionCause.DEMONSTRATION
                        else -> DisruptionCause.OTHER
                    },
                )
            }
        }

        private fun stopPlaceIdOfQuay(network: TransitNetwork, quayId: String): String? {
            val quay = network.quayIndex(quayId) ?: return null
            return network.stopPlaces[network.quayStopPlace[quay]].id
        }

        private val TAG = Regex("<[^>]+>")
        private val SPACES = Regex("\\s+")

        /** "Perturbation" or the description's first words (≤ ~70 chars, cut at a word). */
        internal fun titleFromDescription(html: String): String {
            val text = SPACES.replace(TAG.replace(html, " ").replace("&nbsp;", " "), " ").trim()
            if (text.isEmpty()) return "Perturbation"
            if (text.length <= 70) return text
            val cut = text.substring(0, 70).substringBeforeLast(' ')
            return "$cut…"
        }
    }
}

package com.bustramnantes.app.data.network

import android.content.Context
import com.bustramnantes.app.data.remote.opendata.OpenDataApiService
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.repository.NetworkConnectivityObserver
import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.crosswalk
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.BacsDeLoire
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.gtfs.appending
import com.bustramnantes.transit.walk.Footpaths
import com.bustramnantes.transit.walk.WalkGraph
import com.bustramnantes.transit.walk.WalkGraphFormat
import com.bustramnantes.transit.withFootpaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the static transit network (the whole GTFS timetable, packed - see `:transit`).
 *
 * **Offline-first.** Two candidates are loaded at startup: the bundled seed `assets/seed/network.bin`
 * (regenerated with `./gradlew :transit:packSeed`) and the last network installed by [refresh]
 * (`filesDir/network/<fileId>.bin`, named by a pointer file). The one valid **longest** wins, so an app
 * update shipping a newer seed overrides an older download and vice versa - no seed version number to
 * maintain. Everything (map, search, stop sheet, timetables, itineraries) works from first launch with
 * no network.
 *
 * **Refresh** (daily worker + app open): resolve the current publication through the open-data records
 * API; when its file id differs from the installed network's, download the zip (unmetered only, unless
 * the installed network no longer covers today), convert it on the device - streamed, ~7 s / <128 MB
 * heap on a desktop JVM - with the Bacs de Loire appended, carrying the display-position crosswalk over
 * and computing the quay-to-quay walks on the bundled OSM walking graph, write it next to the current
 * one and swap the pointer atomically. The current network stays in use until the new one has loaded.
 */
@Singleton
class TransitNetworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openData: OpenDataApiService,
    private val feedResolver: GtfsFeedResolver,
    private val connectivity: NetworkConnectivityObserver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "network")
    private val pointer = File(dir, "current")
    private val refreshMutex = Mutex()

    private val _index = MutableStateFlow<NetworkIndex?>(null)

    /**
     * The OSM walking graph (`assets/seed/walk_graph.bin`, ~1.1 MB): read on first use - the first
     * itinerary search or GTFS conversion - and kept for the process. Null if it can't be read
     * (walks then fall back to crow-fly).
     */
    private val walkGraph: WalkGraph? by lazy {
        runCatching { context.assets.open(WALK_GRAPH_ASSET_PATH).buffered().use(WalkGraphFormat::read) }.getOrNull()
    }

    private fun indexOf(network: TransitNetwork) = NetworkIndex(network) { walkGraph }

    /**
     * The Naolib feed [zip] with the Bacs de Loire appended (not in the Naolib GTFS - see
     * [BacsDeLoire]): the current zip from the Loire-Atlantique portal, else the copy bundled at seed
     * time (`assets/seed/bacs_de_loire.zip`). The returned cleanup deletes the temporary bacs file.
     */
    private suspend fun withBacs(zip: File): Pair<GtfsSource, () -> Unit> {
        val bacs = File(context.cacheDir, "bacs_de_loire.zip")
        val fetched = runCatching {
            val listing = openData.download(BacsDeLoire.EXPORTS_URL).string()
            val url = requireNotNull(BacsDeLoire.zipUrlFrom(listing))
            openData.download(url).byteStream().use { input -> bacs.outputStream().use { input.copyTo(it) } }
            // Must open as a GTFS (portal hiccups return HTML).
            java.util.zip.ZipFile(bacs).use { requireNotNull(it.getEntry("routes.txt")) }
        }.isSuccess
        if (!fetched) {
            runCatching { context.assets.open(BACS_ASSET_PATH).use { input -> bacs.outputStream().use { input.copyTo(it) } } }
                .onFailure { bacs.delete(); return GtfsSource.zip(zip) to {} }
        }
        return GtfsSource.zip(zip).appending(GtfsSource.zip(bacs), BacsDeLoire.SPEC) to { bacs.delete() }
    }

    /** [converted] with its quay-to-quay walks computed on the walking graph (when available). */
    private fun withStreetFootpaths(converted: TransitNetwork): TransitNetwork =
        walkGraph?.let { converted.withFootpaths(Footpaths.compute(converted, it)) } ?: converted

    /** The current network index; emits again whenever a refreshed network is installed. */
    val indexes: Flow<NetworkIndex> = _index.filterNotNull()

    private val initialLoad: Deferred<Unit> = scope.async {
        _index.value = indexOf(loadBest())
    }

    init {
        // Warm-up, off the startup path: the first itinerary search would otherwise pay for reading the
        // walking graph (~1.1 MB), attaching every quay to it and building the router's transfers.
        // Redone for each newly installed network (collectLatest drops a warm-up made stale).
        scope.launch(Dispatchers.Default) {
            indexes.collectLatest { index ->
                delay(WARM_UP_DELAY_MS)
                runCatching {
                    index.walkGraph
                    index.quaySnaps
                    index.router
                }
            }
        }
        // App-open check, at most once a day (the daily worker is the main path; this catches a
        // device that rarely runs background work). Unmetered only, like the worker.
        scope.launch {
            initialLoad.await()
            val lastCheck = File(dir, "last_check")
            if (System.currentTimeMillis() - lastCheck.lastModified() < CHECK_INTERVAL_MS) return@launch
            if (refresh(allowMetered = false) is DataResult.Success) {
                dir.mkdirs()
                lastCheck.writeText("")
                lastCheck.setLastModified(System.currentTimeMillis())
            }
        }
    }

    /** The current network index, waiting for the initial load (a few hundred ms) if needed. */
    suspend fun index(): NetworkIndex {
        _index.value?.let { return it }
        initialLoad.await()
        return _index.value ?: indexes.first()
    }

    suspend fun network(): TransitNetwork = index().network

    /** Epoch ms when the installed (downloaded) network was written, or null when running on the seed. */
    suspend fun installedAt(): Long? = withContext(Dispatchers.IO) {
        installedFile()?.takeIf { it.isFile && index().network.feedId == it.nameWithoutExtension }?.lastModified()
    }

    /**
     * Installs the current GTFS publication when it differs from the network in use. Returns
     * `Success(true)` when a new network was installed, `Success(false)` when nothing was needed (or the
     * download was deferred to an unmetered network), `Error` on failure (the current network is kept).
     */
    suspend fun refresh(allowMetered: Boolean = false): DataResult<Boolean> = refreshMutex.withLock {
        runCatching {
            val current = index().network
            val feed = feedResolver.latest()
            if (feed.fileId == current.feedId) return@runCatching false
            val today = LocalDate.now(ZoneId.of(current.timezone))
            val currentStillValid = current.covers(today) && current.covers(today.plusDays(EXPIRY_MARGIN_DAYS))
            if (!allowMetered && currentStillValid && connectivity.isMetered.first()) return@runCatching false
            install(feed, current)
            true
        }.fold(
            onSuccess = { DataResult.Success(it) },
            onFailure = { DataResult.Error(it, it.message) },
        )
    }

    /** Forgets the downloaded network and falls back to the bundled seed (Settings "Effacer"). */
    suspend fun resetToSeed() = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() }
            _index.value = indexOf(readSeed())
        }
    }

    /**
     * Debug builds (Settings): download + convert the current feed on this phone, timed and
     * with the heap sampled every 20 ms, then install it when it is a different publication. The
     * acceptance bar was ≤ ~60 s and ≤ ~150 MB heap on a mid-range phone (desktop JVM: ~7 s, < 128 MB).
     */
    suspend fun debugConvertNow(): String = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val current = index().network
            val feed = feedResolver.latest()
            val zip = File(context.cacheDir, "gtfs-debug.zip")
            try {
                val t0 = System.nanoTime()
                openData.download(feed.url).byteStream().use { input -> zip.outputStream().buffered().use { input.copyTo(it) } }
                val t1 = System.nanoTime()
                val rt = Runtime.getRuntime()
                var peak = rt.totalMemory() - rt.freeMemory()
                val sampler = scope.launch {
                    while (true) {
                        peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
                        kotlinx.coroutines.delay(20)
                    }
                }
                val (source, cleanup) = withBacs(zip)
                val converted = try {
                    withContext(Dispatchers.Default) {
                        withStreetFootpaths(
                            GtfsConverter(source, feedId = feed.fileId, crosswalk = current.crosswalk()).convert(),
                        )
                    }
                } finally {
                    sampler.cancel()
                    cleanup()
                }
                val t2 = System.nanoTime()
                val installed = if (feed.fileId != current.feedId) {
                    val target = File(dir.apply { mkdirs() }, "${feed.fileId}.bin")
                    target.outputStream().buffered().use { NetworkFormat.write(converted, it) }
                    File(dir, "current").writeText(feed.fileId)
                    _index.value = indexOf(converted)
                    true
                } else {
                    false
                }
                val report = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}: GTFS ${zip.length() / 1024} KB downloaded in " +
                    "${(t1 - t0) / 1_000_000} ms, converted in ${(t2 - t1) / 1_000_000} ms, peak heap ${peak / (1 shl 20)} MB " +
                    "(max ${rt.maxMemory() / (1 shl 20)} MB), ${converted.tripCount} trips, valid ${converted.validFrom}..${converted.validUntil}" +
                    if (installed) ", installed" else ", same publication (not reinstalled)"
                android.util.Log.i("TransitNetwork", report)
                report
            } finally {
                zip.delete()
            }
        }
    }

    private suspend fun install(feed: GtfsFeed, current: TransitNetwork) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val zip = File(context.cacheDir, "gtfs-${feed.fileId}.zip")
        try {
            openData.download(feed.url).byteStream().use { input ->
                zip.outputStream().buffered().use { input.copyTo(it) }
            }
            val (source, cleanup) = withBacs(zip)
            val converted = try {
                withContext(Dispatchers.Default) {
                    withStreetFootpaths(GtfsConverter(source, feedId = feed.fileId, crosswalk = current.crosswalk()).convert())
                }
            } finally {
                cleanup()
            }
            val target = File(dir, "${feed.fileId}.bin")
            val tmp = File(dir, "${feed.fileId}.bin.tmp")
            tmp.outputStream().buffered().use { NetworkFormat.write(converted, it) }
            // Re-read what was written: never point at a file that doesn't load.
            val reloaded = tmp.inputStream().buffered().use { NetworkFormat.read(it) }
            if (!tmp.renameTo(target)) throw java.io.IOException("cannot install network file")
            val pointerTmp = File(dir, "current.tmp")
            pointerTmp.writeText(feed.fileId)
            if (!pointerTmp.renameTo(pointer)) throw java.io.IOException("cannot update network pointer")
            _index.value = indexOf(reloaded)
            dir.listFiles()?.filter { it.name.endsWith(".bin") && it != target }?.forEach { it.delete() }
        } finally {
            zip.delete()
        }
    }

    private fun installedFile(): File? =
        pointer.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { File(dir, "$it.bin") }

    private fun readSeed(): TransitNetwork =
        context.assets.open(SEED_ASSET_PATH).buffered().use { NetworkFormat.read(it) }

    private fun loadBest(): TransitNetwork {
        val seed = readSeed()
        val installed = runCatching {
            installedFile()?.takeIf { it.isFile }?.inputStream()?.buffered()?.use { NetworkFormat.read(it) }
        }.getOrNull()
        return if (installed != null && !installed.validUntil.isBefore(seed.validUntil)) installed else seed
    }

    companion object {
        const val SEED_ASSET_PATH = "seed/network.bin"
        const val WALK_GRAPH_ASSET_PATH = "seed/walk_graph.bin"
        const val BACS_ASSET_PATH = "seed/bacs_de_loire.zip"

        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** Let the first screen settle before warming the planner up. */
        private const val WARM_UP_DELAY_MS = 2_000L

        /** Refresh even on a metered network once the installed feed ends within this many days. */
        const val EXPIRY_MARGIN_DAYS = 3L
    }
}

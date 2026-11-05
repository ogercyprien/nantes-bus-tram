package com.bustramnantes.app.data.network

import android.content.Context
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.lines.LineMapFormat
import com.bustramnantes.transit.lines.LineMapSet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The line map the Plan tab draws (`:transit` `lines/`, see its README): one layout per zoom, for the
 * network in use.
 *
 * - **Seed**: `assets/seed/line_map.bin`, built by `:transit:buildSeed` for the bundled network.
 * - **Downloaded network**: the layouts are rebuilt on the phone (off the main thread, at background
 *   priority) as soon as [TransitNetworkRepository] installs a network the seed's layouts don't belong
 *   to, then kept in `filesDir/linemap/<feed id>.bin` for the next launches. The desktop JVM takes
 *   about 11 s for the four layouts; Settings › Débogage measures it on the phone ([debugRebuild]).
 * - **Meanwhile the previous layouts stay on screen** (they carry their own routes and stop ids, so
 *   they draw without the network they came from). Lines that changed in the new timetable show
 *   their old path for those seconds.
 *
 * A file that doesn't read (another format version, a half-written file) is rebuilt.
 */
@Singleton
class LineMapRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkRepository: TransitNetworkRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(context.filesDir, "linemap")
    private val buildMutex = Mutex()
    private val _lineMaps = MutableStateFlow<LineMapSet?>(null)

    /** The current line maps; null only until the first one is read (a few hundred ms at startup). */
    val lineMaps: StateFlow<LineMapSet?> = _lineMaps.asStateFlow()

    init {
        scope.launch {
            networkRepository.indexes.collectLatest { index -> runCatching { ensure(index.network) } }
        }
    }

    private suspend fun ensure(network: TransitNetwork) = buildMutex.withLock {
        if (_lineMaps.value?.feedId == network.feedId) return@withLock
        val seed = runCatching { readSeed() }.getOrNull()
        if (seed?.feedId == network.feedId) { _lineMaps.value = seed; return@withLock }
        val stored = runCatching { File(dir, "${network.feedId}.bin").inputStream().buffered().use(LineMapFormat::read) }.getOrNull()
        if (stored?.feedId == network.feedId) { _lineMaps.value = stored; return@withLock }
        // Something on screen while the new layouts are computed: the previous ones, else the seed's.
        if (_lineMaps.value == null && seed != null) _lineMaps.value = seed
        val built = withContext(Dispatchers.Default) { build(network) }
        _lineMaps.value = built
    }

    /** Builds [network]'s layouts and stores them (written to a temporary file, then renamed). */
    private fun build(network: TransitNetwork): LineMapSet {
        val previous = Thread.currentThread().priority
        Thread.currentThread().priority = Thread.MIN_PRIORITY
        try {
            val set = LineMapSet.build(network)
            dir.mkdirs()
            val target = File(dir, "${network.feedId}.bin")
            val tmp = File(dir, "${network.feedId}.bin.tmp")
            runCatching {
                tmp.outputStream().buffered().use { LineMapFormat.write(set, it) }
                if (tmp.renameTo(target)) dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            }
            return set
        } finally {
            Thread.currentThread().priority = previous
        }
    }

    private fun readSeed(): LineMapSet = context.assets.open(SEED_ASSET_PATH).buffered().use(LineMapFormat::read)

    /**
     * Debug builds (Settings): rebuilds the current network's layouts on this phone, timed and with
     * the heap sampled every 20 ms, and installs them. Returns the report (also logged).
     */
    suspend fun debugRebuild(): String = buildMutex.withLock {
        val network = networkRepository.network()
        val rt = Runtime.getRuntime()
        var peak = rt.totalMemory() - rt.freeMemory()
        val sampler = scope.launch {
            while (true) {
                peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
                kotlinx.coroutines.delay(20)
            }
        }
        val t0 = System.nanoTime()
        val set = try {
            withContext(Dispatchers.Default) { build(network) }
        } finally {
            sampler.cancel()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        _lineMaps.value = set
        val report = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}: tracé des lignes recalculé en $ms ms, " +
            "pic de mémoire ${peak / (1 shl 20)} Mo (max ${rt.maxMemory() / (1 shl 20)} Mo), " +
            set.layouts.entries.joinToString { (z, m) -> "z$z ${m.segments.size} segments" }
        android.util.Log.i("LineMap", report)
        report
    }

    companion object {
        const val SEED_ASSET_PATH = "seed/line_map.bin"
    }
}

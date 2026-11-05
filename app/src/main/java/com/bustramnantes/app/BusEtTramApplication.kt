package com.bustramnantes.app

import android.app.Application
import com.bustramnantes.app.di.MapTileClient
import com.bustramnantes.app.worker.BackgroundWorkScheduler
import com.bustramnantes.app.data.repository.FavoriteRekeyer
import com.bustramnantes.app.data.network.LineMapRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Application entry point. Hilt's dependency graph is rooted here.
 *
 * [onCreate] performs the eager, process-wide startup steps:
 *  - [BackgroundWorkScheduler.schedule] - enqueues the daily cache-refresh
 *    task and the widget-refresh chain. Both underlying WorkManager requests use `KEEP` policies,
 *    so re-running `onCreate` (e.g. process restarts) never duplicates work.
 *  - [HttpRequestUtil.setOkHttpClient] - routes MapLibre's HTTP (the OSM basemap) through our client
 *    so tiles are disk-cached. Set once here, before any `MapView` is created (MapLibre reads it
 *    lazily). The lines, stop markers and fonts are local and never go through HTTP.
 *  - [FavoriteRekeyer.run] - moves favorites whose StopPlace a new GTFS publication retired.
 *  - [LineMapRepository] is created here so it starts watching the network at once: after a GTFS
 *    refresh it rebuilds the line map in the background, whether or not the Plan tab is open.
 *
 * Field injection into a `@HiltAndroidApp` `Application` is populated by Hilt's generated base
 * class before this `onCreate()` body runs, as long as `super.onCreate()` is called first.
 */
@HiltAndroidApp
class BusEtTramApplication : Application() {

    @Inject
    @MapTileClient
    lateinit var mapTileClient: OkHttpClient

    @Inject
    lateinit var favoriteRekeyer: FavoriteRekeyer

    @Inject
    lateinit var lineMapRepository: LineMapRepository

    /** Process-wide scope for fire-and-forget startup work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        BackgroundWorkScheduler.schedule(this)
        // MapLibre MUST be initialized before HttpRequestUtil.setOkHttpClient: that call triggers
        // HttpRequestImpl's static init, which calls MapLibre.getApplicationContext() and throws if
        // MapLibre.getInstance() has not run yet (else the app crashes here at launch, before any
        // screen). getInstance is idempotent, so the MapView's later getInstance is a no-op.
        MapLibre.getInstance(this)
        // No MapLibre.setConnected(true) any more: it only existed so offline requests still reached
        // OkHttp, where an interceptor served the bundled official line tiles. Our lines are GeoJSON
        // sources and the fonts asset:// files, which MapLibre reads whatever the connectivity; the
        // basemap offline comes from MapLibre's own ambient cache, as on any MapLibre app.
        HttpRequestUtil.setOkHttpClient(mapTileClient)
        // Favorites/recent searches on a StopPlace the network no longer has -> its successor (usually a no-op).
        appScope.launch { runCatching { favoriteRekeyer.run() } }
    }
}

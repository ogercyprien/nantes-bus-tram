package com.bustramnantes.app

import android.app.Application
import com.bustramnantes.app.di.MapTileClient
import com.bustramnantes.app.worker.BackgroundWorkScheduler
import com.bustramnantes.app.data.repository.FavoriteRekeyer
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
 *  - [HttpRequestUtil.setOkHttpClient] - routes ALL of MapLibre's HTTP through our client so the
 *    bundled line-raster seed is served offline/instantly (via its interceptor) and map tiles are
 *    disk-cached. Set once here, before any `MapView` is created (MapLibre reads it lazily).
 *  - [FavoriteRekeyer.run] - moves favorites whose StopPlace a new GTFS publication retired.
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
        // Force MapLibre to always ATTEMPT tile requests. By default it auto-detects connectivity and,
        // when offline (e.g. airplane mode), short-circuits requests BEFORE they reach the OkHttp
        // stack - so our SeedTileInterceptor never runs and the bundled offline line tiles never show.
        // With this override every request reaches OkHttp: the interceptor serves seeded tiles offline,
        // and genuinely-remote tiles (basemap, un-seeded zooms) just fail fast as they would anyway.
        MapLibre.setConnected(true)
        HttpRequestUtil.setOkHttpClient(mapTileClient)
        // Favorites/recent searches on a StopPlace the network no longer has -> its successor (usually a no-op).
        appScope.launch { runCatching { favoriteRekeyer.run() } }
    }
}

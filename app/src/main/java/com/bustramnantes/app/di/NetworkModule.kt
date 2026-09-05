package com.bustramnantes.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.bustramnantes.app.data.remote.GeocodingApiService
import com.bustramnantes.app.data.remote.opendata.OpenDataApiService
import com.bustramnantes.app.data.local.MbTilesTileStore
import com.bustramnantes.app.data.remote.interceptor.SeedTileInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val GEOCODING_BASE_URL = "https://data.geopf.fr/"
private const val OPEN_DATA_BASE_URL = "https://data.nantesmetropole.fr/"

/**
 * Explicit OkHttp timeouts. OkHttp's per-socket defaults (10 s connect/read/write) do **not** cap
 * a whole call: on a low-bandwidth link where bytes trickle in just under the read window, each
 * partial read resets the read timer, so without an overall [callTimeout] a single request can hang
 * for minutes and leave a loading spinner spinning indefinitely. [CALL_TIMEOUT_SEC] is that hard
 * ceiling - a slow call now fails deterministically into the app's `DataResult.Error` →
 * friendly-message path instead of hanging. Kept under the Home 30 s poll interval so a stalled
 * poll fails and is replaced rather than being cancelled mid-flight.
 */
private const val CONNECT_TIMEOUT_SEC = 10L
private const val READ_TIMEOUT_SEC = 20L
private const val WRITE_TIMEOUT_SEC = 20L
private const val CALL_TIMEOUT_SEC = 25L

/** On-disk HTTP cache size for the geocoding client. */
private const val HTTP_CACHE_SIZE_BYTES = 15L * 1024 * 1024

/**
 * On-disk cache for the MapLibre client. Larger than [HTTP_CACHE_SIZE_BYTES] because it holds map
 * imagery (basemap tiles, un-seeded/high-zoom line-raster tiles, vector tiles, glyphs) rather than
 * small JSON payloads, so tiles seen once keep loading offline and instantly on later views.
 */
private const val MAP_TILE_CACHE_SIZE_BYTES = 50L * 1024 * 1024

/** Applies the shared timeout policy (see [CALL_TIMEOUT_SEC]) to every client the module builds. */
private fun OkHttpClient.Builder.withStandardTimeouts(): OkHttpClient.Builder =
    connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)

/** Qualifies the [OkHttpClient] for the Okina real-time gateway (SIRI, GTFS-RT). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OkinaHttpClient

/** Qualifies the [Retrofit] instance for the Nantes Métropole open-data portal (GTFS download). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenDataRetrofit

/** Qualifies the [Retrofit] instance for the data.geopf.fr geocoder. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeocodingRetrofit

/**
 * Qualifies the [OkHttpClient] handed to MapLibre (via `HttpRequestUtil.setOkHttpClient` in the
 * Application). It carries the [SeedTileInterceptor] (serves bundled line tiles offline/instantly)
 * plus an on-disk tile cache; it is a bare client, not a Retrofit instance, since MapLibre drives
 * its own HTTP.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MapTileClient

/**
 * Wires the HTTP layer - every remote is public and keyless:
 *
 * - **Open data** ([OpenDataApiService], `data.nantesmetropole.fr`): the GTFS publication (records
 *   API + zip download).
 * - **Okina** (bare [OkHttpClient], `api.okina.fr`): real-time SIRI StopMonitoring + GTFS-RT.
 * - **Map tiles** ([MapTileClient]): line rasters/vector tiles from `plan-app.naolib.fr`, basemap.
 * - **Geocoding** ([GeocodingApiService], `data.geopf.fr`): address search.
 *
 * The Retrofit services share one [Json] instance (`ignoreUnknownKeys = true`, so additive or
 * undocumented fields never crash deserialization) via the kotlinx.serialization converter.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // A present-but-null (or wrong-enum) value for a field that has a default is coerced to that
        // default instead of throwing - so one odd field in a record doesn't fail the whole decode.
        coerceInputValues = true
    }

    /**
     * Debug-vs-release logging level is derived from [ApplicationInfo.FLAG_DEBUGGABLE] (via the
     * injected application [Context]) rather than a generated `BuildConfig.DEBUG` field, since the
     * `app` module does not currently opt into the `buildConfig` build feature and this module is
     * scoped to touching only `data/remote/` + `di/NetworkModule.kt`.
     */
    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(@ApplicationContext context: Context): HttpLoggingInterceptor {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return HttpLoggingInterceptor().apply {
            level = if (isDebuggable) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
    }

    @Provides
    @Singleton
    @GeocodingRetrofit
    fun provideGeocodingRetrofit(
        @ApplicationContext context: Context,
        json: Json,
        loggingInterceptor: HttpLoggingInterceptor,
    ): Retrofit {
        val client = OkHttpClient.Builder()
            .withStandardTimeouts()
            .cache(Cache(File(context.cacheDir, "http_cache_geocoding"), HTTP_CACHE_SIZE_BYTES))
            .addInterceptor(loggingInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl(GEOCODING_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    /**
     * Open-data portal: the GTFS records API (small JSON) and the ~26 MB zip download. No logging
     * interceptor - BODY logging would buffer the whole zip in memory - and a generous call timeout
     * (the download runs in the daily worker, on unmetered networks).
     */
    @Provides
    @Singleton
    @OpenDataRetrofit
    fun provideOpenDataRetrofit(json: Json): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
        return Retrofit.Builder()
            .baseUrl(OPEN_DATA_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    /**
     * Okina real-time gateway. No logging interceptor (a SIRI batch is ~0.6-1 MB of XML) and no HTTP
     * cache (always live). Standard timeouts.
     */
    @Provides
    @Singleton
    @OkinaHttpClient
    fun provideOkinaHttpClient(): OkHttpClient = OkHttpClient.Builder().withStandardTimeouts().build()

    @Provides
    @Singleton
    fun provideOpenDataApiService(@OpenDataRetrofit retrofit: Retrofit): OpenDataApiService =
        retrofit.create(OpenDataApiService::class.java)

    /**
     * The client MapLibre uses for every map HTTP request. [SeedTileInterceptor] short-circuits
     * bundled line-raster tiles (instant/offline); everything else (basemap, high-zoom tiles,
     * vector tiles, glyphs, TileJSON) goes to the network and is retained by the on-disk cache.
     */
    @Provides
    @Singleton
    @MapTileClient
    fun provideMapTileOkHttpClient(
        @ApplicationContext context: Context,
        tileStore: MbTilesTileStore,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .withStandardTimeouts()
            .cache(Cache(File(context.cacheDir, "http_cache_map"), MAP_TILE_CACHE_SIZE_BYTES))
            .addInterceptor(SeedTileInterceptor(tileStore))
            // No logging interceptor here on purpose: responses are binary tile imagery, and
            // BODY-level logging would flood logcat with megabytes of PNG bytes per map pan.
            .build()

    @Provides
    @Singleton
    fun provideGeocodingApiService(@GeocodingRetrofit retrofit: Retrofit): GeocodingApiService =
        retrofit.create(GeocodingApiService::class.java)
}

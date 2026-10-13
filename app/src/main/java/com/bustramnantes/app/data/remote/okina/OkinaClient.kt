package com.bustramnantes.app.data.remote.okina

import android.util.Xml
import com.bustramnantes.app.di.OkinaHttpClient
import com.bustramnantes.transit.realtime.GtfsRealtime
import com.bustramnantes.transit.realtime.ServiceAlerts
import com.bustramnantes.transit.realtime.Siri
import com.bustramnantes.transit.realtime.SiriVisit
import com.bustramnantes.transit.realtime.TripUpdatesFeed
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP 429 from the Okina gateway: the 1 req / 30 s budget of this IP is spent (maybe by another device). */
class RateLimitedException : IOException("Okina rate limit exceeded")

/**
 * The keyless Okina real-time endpoints - SIRI StopMonitoring, GTFS-RT TripUpdates and Alerts
 * (SOURCES.md §5). Callers must go through the request budgets (`RealtimeGateway`,
 * `GtfsRtDisruptionRepository`): this class only performs the calls. OkHttp negotiates gzip
 * transparently (SIRI XML ~18×, TripUpdates ~4.5× smaller on the wire).
 */
@Singleton
class OkinaClient @Inject constructor(
    @OkinaHttpClient private val client: OkHttpClient,
) {
    /**
     * One batched StopMonitoring request for [quayIds] (`FR_NAOLIB:Quay:<n>`). [bulk]: the whole
     * network (~2,500 quays: 1.2 MB gzipped, 15.8 MB of XML, 8-10 s server time) -
     * given a longer call timeout than the standard 25 s.
     */
    suspend fun stopMonitoring(quayIds: Collection<String>, bulk: Boolean = false): List<SiriVisit> = withContext(Dispatchers.IO) {
        val client = if (bulk) bulkClient else client
        val body = Siri.stopMonitoringRequest(quayIds, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
        val request = Request.Builder()
            .url(Siri.ENDPOINT)
            .post(body.toRequestBody(XML))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw RateLimitedException()
            if (!response.isSuccessful) throw IOException("SIRI HTTP ${response.code}")
            val reader = response.body?.charStream() ?: throw IOException("empty SIRI response")
            Siri.parseStopMonitoring(reader, Xml.newPullParser())
        }
    }

    /** The whole network's GTFS-RT TripUpdates. */
    suspend fun tripUpdates(): TripUpdatesFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(GtfsRealtime.TRIP_UPDATES_URL).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw RateLimitedException()
            if (!response.isSuccessful) throw IOException("GTFS-RT HTTP ${response.code}")
            GtfsRealtime.decodeTripUpdates(response.body?.bytes() ?: throw IOException("empty GTFS-RT response"))
        }
    }

    /** The raw GTFS-RT Alerts message (decoded and kept on disk by the disruption repository). */
    suspend fun alerts(): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(ServiceAlerts.ALERTS_URL).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw RateLimitedException()
            if (!response.isSuccessful) throw IOException("GTFS-RT alerts HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("empty GTFS-RT alerts response")
        }
    }

    private val bulkClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(90, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private companion object {
        val XML = "application/xml".toMediaType()
    }
}

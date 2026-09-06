package com.bustramnantes.app.data.remote.interceptor

import com.bustramnantes.app.data.local.MbTilesTileStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Serves the bundled line-raster tiles from [MbTilesTileStore] before the network, for the MapLibre
 * HTTP client only (wired in `NetworkModule`). When MapLibre requests a `v5` day raster tile
 * (`https://plan-app.naolib.fr/data/raster/v5/{z}/{x}/{y}.png`) and that tile is in the seed bundle,
 * this returns it as a synthetic `200` response and never touches the network - so the lines paint
 * instantly and work fully offline within the seeded area/zoom. Anything not in the bundle (other
 * hosts, the OSM basemap, vector tiles, glyphs, or un-seeded zoom levels) falls through to
 * `chain.proceed`, where the client's on-disk cache still applies.
 *
 * The seed is a build-time snapshot; it is intentionally preferred over the network within its zoom
 * range (line geometry changes rarely, mirroring the stops/lines JSON seed). It is refreshed by
 * shipping a new bundle + bumping the store's seed version, not per-request.
 */
class SeedTileInterceptor(
    private val tileStore: MbTilesTileStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host == RASTER_HOST) {
            RASTER_PATH.matchEntire(url.encodedPath)?.let { match ->
                val (z, x, y) = match.destructured
                tileStore.tile(z.toInt(), x.toInt(), y.toInt())?.let { bytes ->
                    return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (seed)")
                        .header("Content-Type", PNG_MEDIA_TYPE)
                        .body(bytes.toResponseBody(PNG_MEDIA_TYPE.toMediaType()))
                        .build()
                }
            }
        }
        return chain.proceed(request)
    }

    private companion object {
        const val RASTER_HOST = "plan-app.naolib.fr"
        const val PNG_MEDIA_TYPE = "image/png"

        /** `/data/raster/v5/{z}/{x}/{y}.png` - the day-theme raster line tiles (see MapStyleIds). */
        val RASTER_PATH = Regex("""/data/raster/v5/(\d+)/(\d+)/(\d+)\.png""")
    }
}

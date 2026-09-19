package com.bustramnantes.app.data.remote

import com.bustramnantes.app.data.remote.dto.geocoding.GeocodingResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * French government Géoplateforme / BAN address geocoder. Base URL
 * `https://data.geopf.fr/`. Unauthenticated - no static-header or auth interceptor.
 */
interface GeocodingApiService {

    /** `GET /geocodage/search/`. [lon]/[lat] are optional biasing coordinates. */
    @GET("geocodage/search/")
    suspend fun searchAddresses(
        @Query("q") query: String,
        @Query("lon") lon: Double? = null,
        @Query("lat") lat: Double? = null,
        @Query("limit") limit: Int = 8,
        @Query("autocomplete") autocomplete: Int = 1,
    ): GeocodingResponseDto
}

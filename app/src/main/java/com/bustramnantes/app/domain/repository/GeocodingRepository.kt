package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.DataResult

/** Address autocomplete via data.geopf.fr. Unauthenticated, no caching. */
interface GeocodingRepository {

    /**
     * @param lat/[lon] optional biasing coordinates (`lon`/`lat` query params).
     * Debouncing (300 ms) is a presentation-layer concern, not enforced here.
     */
    suspend fun searchAddresses(query: String, lat: Double? = null, lon: Double? = null): DataResult<List<AddressResult>>
}

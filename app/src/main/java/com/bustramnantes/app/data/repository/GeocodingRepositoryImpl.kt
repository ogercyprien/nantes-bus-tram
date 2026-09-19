package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.remote.GeocodingApiService
import com.bustramnantes.app.data.remote.dto.geocoding.GeocodingFeatureDto
import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.repository.GeocodingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [GeocodingApiService]. Unauthenticated, no caching.
 *
 * The upstream BAN geocoder (`data.geopf.fr`) is a nationwide address index with no way to hard-
 * restrict it to a metropolitan area server-side beyond `lat`/`lon` proximity *biasing* (it still
 * scores a same-name match a thousand kilometers away above a lower-text-score match next door).
 * This app only ever needs Nantes Métropole addresses, so [NANTES_LATITUDE]/[NANTES_LONGITUDE] is
 * always sent as the bias (falling back to it when the caller has no device location yet), and
 * results outside [NANTES_METROPOLE_RADIUS_KM] are dropped rather than shown - e.g. searching
 * "commerce" would otherwise surface "Commerces" in Isère/Somme ahead of "Place du Commerce, Nantes".
 */
@Singleton
class GeocodingRepositoryImpl @Inject constructor(
    private val geocodingApiService: GeocodingApiService,
) : GeocodingRepository {

    override suspend fun searchAddresses(
        query: String,
        lat: Double?,
        lon: Double?,
    ): DataResult<List<AddressResult>> = runCatching {
        geocodingApiService.searchAddresses(
            query = query,
            lon = lon ?: NANTES_LONGITUDE,
            lat = lat ?: NANTES_LATITUDE,
        ).features
            .mapNotNull { it.toDomainOrNull() }
            .filter { isWithinNantesArea(it.lat, it.lon, radiusKm = NANTES_METROPOLE_RADIUS_KM) }
    }.toDataResult()

    companion object {
        // Mirrored from RepositoryMappingUtils.kt's own (file-private) copy - see this class's doc
        // comment and that file's identical note on why this ~2-line duplication beats a shared dep.
        private const val NANTES_LATITUDE = 47.2184
        private const val NANTES_LONGITUDE = -1.5536
        private const val NANTES_METROPOLE_RADIUS_KM = 25.0
    }
}

/** `null` for a feature with a malformed coordinate pair, rather than failing the whole search. */
private fun GeocodingFeatureDto.toDomainOrNull(): AddressResult? {
    val coordinates = geometry.coordinates
    if (coordinates.size < 2) return null
    return AddressResult(
        label = properties.label,
        city = properties.city.orEmpty(),
        lat = coordinates[1],
        lon = coordinates[0],
    )
}

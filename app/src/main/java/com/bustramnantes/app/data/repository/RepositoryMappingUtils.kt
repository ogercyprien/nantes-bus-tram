package com.bustramnantes.app.data.repository

import com.bustramnantes.app.domain.model.DataResult
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small helpers shared across the `data/repository/` implementations. Kept private to this
 * package/module so no repository implementation needs to duplicate this logic inline.
 */

/** Converts a [Result] (from `runCatching { ... }`) into the domain-level [DataResult] wrapper. */
internal fun <T> Result<T>.toDataResult(): DataResult<T> = fold(
    onSuccess = { DataResult.Success(it) },
    onFailure = { DataResult.Error(it, it.message) },
)

/**
 * Great-circle distance in meters. Deliberately duplicated (not imported) from
 * `presentation/common/utils/GeoUtils.kt`: that file lives in the presentation layer, and having
 * `data/repository/` depend on it would invert Clean Architecture's dependency direction
 * (presentation may depend on data/domain, never the reverse). The duplicated logic is ~10 lines.
 */
private const val EARTH_RADIUS_METERS = 6_371_000.0

internal fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/** Nantes city-center coordinates, mirrored from `presentation/common/utils/GeoUtils.kt` (see above). */
private const val NANTES_LATITUDE = 47.2184
private const val NANTES_LONGITUDE = -1.5536

/** "Within ~50 km of Nantes" gate used to decide proximity-sort vs alphabetical. */
internal fun isWithinNantesArea(lat: Double, lon: Double, radiusKm: Double = 50.0): Boolean =
    haversineDistanceMeters(lat, lon, NANTES_LATITUDE, NANTES_LONGITUDE) <= radiusKm * 1000.0

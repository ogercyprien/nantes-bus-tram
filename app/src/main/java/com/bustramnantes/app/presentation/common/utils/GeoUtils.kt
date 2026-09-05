package com.bustramnantes.app.presentation.common.utils

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Nantes city-center coordinates (default camera / proximity checks). */
const val NANTES_LATITUDE = 47.2184
const val NANTES_LONGITUDE = -1.5536

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Great-circle distance between two lat/lon points, in meters. Pure Kotlin, no dependency.
 */
fun haversineDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)

    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/**
 * Whether `(lat, lon)` is within [radiusKm] of Nantes - the "within 50 km of Nantes" proximity
 * gate used to decide whether to sort search results by distance or default the
 * map camera to the user's location.
 */
fun isWithinNantesArea(
    lat: Double,
    lon: Double,
    radiusKm: Double = 50.0,
): Boolean = haversineDistanceMeters(lat, lon, NANTES_LATITUDE, NANTES_LONGITUDE) <= radiusKm * 1000.0

package com.bustramnantes.app.presentation.search.location

/**
 * Abstraction over the device location lookups the "À proximité" / Search surfaces need, so the
 * ViewModels can be unit-tested without the Android `LocationManager` (the concrete
 * [UserLocationProvider] talks to framework APIs that don't exist on the JVM test runtime).
 *
 * Coordinates are returned as `(latitude, longitude)`; `null` means "no fix available" (permission
 * missing, no provider enabled, or nothing cached yet). See [UserLocationProvider] for the semantics
 * of last-known vs. fresh fixes.
 */
interface LocationProvider {

    /** Whether the fine or coarse location permission is currently granted. */
    fun hasLocationPermission(): Boolean

    /** Best cached fix across providers - instant but possibly stale. `null` if none is available. */
    fun getLastKnownLocation(): Pair<Double, Double>?

    /** Actively-requested current fix, falling back to the last-known fix. `null` if none is available. */
    suspend fun getCurrentLocation(): Pair<Double, Double>?
}

package com.bustramnantes.app.presentation.search.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimal, dependency-free (no Play Services / FusedLocationProvider) last-known-location lookup
 * used to decide whether Search's Arrêt-mode results are proximity-sorted or alphabetical, and to
 * bias Adresse-mode geocoding queries ("within 50 km of Nantes").
 *
 * Intentionally does not request runtime permissions itself - that requires an Activity/Composable
 * context (`rememberLauncherForActivityResult`). Callers request the permission from the UI layer
 * and call [getLastKnownLocation] afterwards; if permission is still missing this simply returns
 * `null` and callers fall back to alphabetical sorting / unbiased geocoding, matching the
 * repository contracts' documented `userLat`/`userLon` optionality.
 */
class UserLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    override fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best last-known fix across all enabled providers (no active location request - a one-shot
     * "do we already roughly know where the user is" check is enough for search sorting/biasing).
     * Returns `null` if permission is missing, no provider is enabled, or none has a cached fix.
     */
    @SuppressLint("MissingPermission")
    override fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        var best: Location? = null
        for (provider in runCatching { locationManager.allProviders }.getOrDefault(emptyList())) {
            val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || location.accuracy < best.accuracy) {
                best = location
            }
        }
        return best?.let { it.latitude to it.longitude }
    }

    /**
     * Requests a single **fresh** location fix (an active request), falling back to
     * [getLastKnownLocation] if permission is missing, no provider is enabled, the request times out,
     * or it yields nothing. Unlike [getLastKnownLocation] - a cached fix that is often stale right
     * after the app opens (the very problem behind "it shows stops near my old location") - this asks
     * the OS for the current position. Used when the user explicitly refreshes "À proximité" and when
     * the Search tab is (re)opened, so the proximity ordering reflects where the user actually is now.
     *
     * Uses [LocationManagerCompat.getCurrentLocation] (back-compat to API 26, no Play Services) and
     * bounds the wait so a provider that never fixes can't hang. Crucially, when a last-known fix is
     * already available it only waits a short grace window ([FRESH_FIX_GRACE_MS]) for a fresher one:
     * a good GPS/network signal returns well inside that window and is used, but a provider that
     * isn't delivering degrades to the last-known fix near-instantly instead of stalling the caller
     * (e.g. this app is GMS-free, and on many devices `NETWORK_PROVIDER` is enabled yet never yields a
     * fix - an unconditional full-timeout wait would make pull-to-refresh appear to hang for 8s). Only
     * when there is no last-known fix to fall back on do we wait the full [CURRENT_LOCATION_TIMEOUT_MS].
     */
    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val lastKnown = getLastKnownLocation()
        val provider = PROVIDER_PREFERENCE.firstOrNull { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        } ?: return lastKnown

        val activeFixTimeoutMs = if (lastKnown != null) FRESH_FIX_GRACE_MS else CURRENT_LOCATION_TIMEOUT_MS
        val fresh = withTimeoutOrNull(activeFixTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(context),
                ) { location -> if (continuation.isActive) continuation.resume(location) }
            }
        }
        return fresh?.let { it.latitude to it.longitude } ?: lastKnown
    }

    private companion object {
        /**
         * Full active-fix wait, used only when there is no last-known fix to fall back on (nothing to
         * show yet, so it's worth waiting for a first fix). Also caps a stuck provider from hanging.
         */
        const val CURRENT_LOCATION_TIMEOUT_MS = 8_000L

        /**
         * Grace window for a *fresher* fix when a last-known fix already exists. A good signal wins the
         * race inside this window; otherwise we return the (usually fresh-enough) last-known fix
         * immediately rather than blocking the user - matching the "should resolve almost instantly on
         * a good connection" expectation for pull-to-refresh.
         */
        const val FRESH_FIX_GRACE_MS = 2_000L

        /** Providers tried in order for an active fix: network (fast, indoors) before GPS, then passive. */
        val PROVIDER_PREFERENCE = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}

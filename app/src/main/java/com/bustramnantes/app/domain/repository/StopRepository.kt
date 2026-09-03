package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Stop
import kotlinx.coroutines.flow.Flow

/**
 * Local-first access to the stop cache built from `logical_stops.geojson`.
 * Stop search never hits the network directly - it reads from the Room cache maintained here.
 */
interface StopRepository {

    /** Reactive view of the full local stop cache, e.g. to feed the Map screen's GeoJSON source. */
    fun observeStops(): Flow<List<Stop>>

    /**
     * Fuzzy/prefix match on stop names against the local cache. When
     * [userLat]/[userLon] are supplied and within ~50 km of Nantes, results are proximity-sorted;
     * otherwise alphabetical. Favorites are surfaced first by the caller (presentation layer),
     * not by this method.
     */
    suspend fun searchStops(query: String, userLat: Double? = null, userLon: Double? = null): List<Stop>

    suspend fun getStopById(id: String): Stop?

    /**
     * The [limit] stops physically closest to `(lat, lon)`, nearest first, from the installed network.
     * Always distance-sorted (unlike [searchStops], which only proximity-sorts within ~50 km of
     * Nantes) - it backs the "À proximité" tab, whose whole purpose is "the stops closest to me".
     */
    suspend fun getNearbyStops(lat: Double, lon: Double, limit: Int): List<Stop>

    /** Checks for a newer GTFS publication and installs it (one network for stops and lines). */
    suspend fun refreshStops(): DataResult<Unit>

    /** Forgets the downloaded network, back to the bundled seed (used together with [refreshStops] by "Effacer"). */
    suspend fun clearStopsCache()

    /** Epoch ms the installed network was written, or `null` when running on the seed. */
    suspend fun getStopsCacheTimestamp(): Long?
}

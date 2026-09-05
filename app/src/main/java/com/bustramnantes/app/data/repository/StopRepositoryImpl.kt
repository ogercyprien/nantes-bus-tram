package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.data.network.TransitNetworkRepository
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.util.normalizeForMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stops straight from the static GTFS network ([TransitNetworkRepository]): the bundled seed on first
 * launch, the daily-refreshed open-data feed afterwards - so the map, search and itinerary fields work
 * offline from the very first start. No Room cache: the network is already an in-memory, offline copy,
 * and GTFS stop names are current.
 *
 * Search keys are precomputed per network (see [SearchIndex]): each stop's accent-folded name plus its
 * [Stop.aliases].
 */
@Singleton
class StopRepositoryImpl @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : StopRepository {

    @Volatile
    private var searchIndex: SearchIndex? = null

    private suspend fun searchIndex(): SearchIndex {
        val index = networkRepository.index()
        searchIndex?.takeIf { it.source === index }?.let { return it }
        return SearchIndex(index).also { searchIndex = it }
    }

    override fun observeStops(): Flow<List<Stop>> = networkRepository.indexes.map { it.stops }

    override suspend fun searchStops(query: String, userLat: Double?, userLon: Double?): List<Stop> {
        // Accent-insensitive substring match ("aerop" -> "Aéroport", "leon jost" -> "Léon Jost").
        val normalizedQuery = normalizeForMatch(query)
        val index = searchIndex()
        val matches = if (normalizedQuery.isBlank()) {
            index.entries.map { it.stop }
        } else {
            index.entries.filter { entry -> entry.searchKeys.any { it.contains(normalizedQuery) } }.map { it.stop }
        }
        return if (userLat != null && userLon != null && isWithinNantesArea(userLat, userLon)) {
            matches.sortedBy { haversineDistanceMeters(userLat, userLon, it.lat, it.lon) }
        } else {
            matches.sortedBy { it.name }
        }
    }

    override suspend fun getStopById(id: String): Stop? = networkRepository.index().stop(id)

    override suspend fun getNearbyStops(lat: Double, lon: Double, limit: Int): List<Stop> =
        networkRepository.index().stops
            .sortedBy { haversineDistanceMeters(lat, lon, it.lat, it.lon) }
            .take(limit)

    override suspend fun refreshStops(): DataResult<Unit> =
        when (val result = networkRepository.refresh(allowMetered = true)) {
            is DataResult.Success -> DataResult.Success(Unit)
            is DataResult.Error -> result
        }

    override suspend fun clearStopsCache() = networkRepository.resetToSeed()

    override suspend fun getStopsCacheTimestamp(): Long? = networkRepository.installedAt()
}

/** Per-network search index: every stop with every accent-folded label it can be found by. */
private class SearchIndex(val source: NetworkIndex) {
    val entries: List<IndexedStop> = source.stops.map { stop ->
        IndexedStop(
            stop = stop,
            searchKeys = (listOf(stop.name) + stop.aliases.filter { it.isNotBlank() }).map(::normalizeForMatch),
        )
    }
}

private data class IndexedStop(val stop: Stop, val searchKeys: List<String>)

/** How close (metres) two same-name stops must be to be treated as the same physical stop and merged. */
private const val STOP_MERGE_RADIUS_METERS = 150.0

/**
 * Collapses same-name, physically-adjacent stops into one - the way the official app shows a single
 * "Joliverie" where GTFS has two StopPlaces (line 30 / lines 4, 30, 42…) ~110 m apart. Only stops that
 * share an accent-insensitive name **and** sit within [STOP_MERGE_RADIUS_METERS] of each other are
 * merged, so two genuinely different places that happen to share a name (e.g. the two "Jean Jaurès"
 * stops ~9 km apart) stay separate.
 *
 * The representative of a merged cluster is the member serving the most lines; it keeps its id and
 * coordinates. [Stop.linkedLines] becomes the union across the cluster and
 * [Stop.memberIds] lists every member, so departures/served lines cover all their quays.
 */
internal fun mergeDuplicateStops(stops: List<Stop>): List<Stop> {
    val merged = ArrayList<Stop>(stops.size)
    for ((_, sameName) in stops.groupBy { normalizeForMatch(it.name) }) {
        if (sameName.size == 1) {
            merged += sameName[0]
            continue
        }
        for (cluster in clusterByProximity(sameName, STOP_MERGE_RADIUS_METERS)) {
            merged += if (cluster.size == 1) cluster[0] else mergeCluster(cluster)
        }
    }
    return merged
}

/** Greedy single-link clustering: each stop joins the first existing cluster whose seed is within
 * [radiusMeters], else it seeds a new cluster. Adequate for the tiny same-name groups here. */
private fun clusterByProximity(stops: List<Stop>, radiusMeters: Double): List<List<Stop>> {
    val clusters = mutableListOf<MutableList<Stop>>()
    for (stop in stops) {
        val target = clusters.firstOrNull { cluster ->
            haversineDistanceMeters(cluster[0].lat, cluster[0].lon, stop.lat, stop.lon) <= radiusMeters
        }
        if (target != null) target += stop else clusters += mutableListOf(stop)
    }
    return clusters
}

private fun mergeCluster(cluster: List<Stop>): Stop {
    val representative = cluster.maxByOrNull { it.linkedLines.size } ?: cluster.first()
    val unionLines = LinkedHashSet(representative.linkedLines)
    cluster.forEach { unionLines.addAll(it.linkedLines) }
    return representative.copy(
        linkedLines = unionLines.toList(),
        accessible = cluster.any { it.accessible },
        aliases = cluster.flatMap { it.aliases }.distinct(),
        memberIds = (representative.memberIds + cluster.flatMap { it.memberIds }).distinct(),
    )
}

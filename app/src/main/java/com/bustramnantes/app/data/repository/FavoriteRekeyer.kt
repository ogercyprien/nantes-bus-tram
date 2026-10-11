package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.local.dao.FavoriteDao
import com.bustramnantes.app.data.local.dao.RecentSearchDao
import com.bustramnantes.app.data.local.entity.FavoriteEntity
import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.data.network.TransitNetworkRepository
import com.bustramnantes.app.domain.util.normalizeForMatch
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.servedRoutes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps user data pointing at existing stops when a new GTFS publication retires a StopPlace id (a
 * stop renumbered, or split in two). Runs at startup once the network is loaded; idempotent, and finds
 * nothing to do as long as every saved id still resolves.
 *
 * A favorite whose stop id the network no longer knows moves to a StopPlace bearing the same name
 * ([rekeyFavorite] picks among them) and keeps the retired id in `legacyStopId`. Recent searches are
 * simply rewritten (they hold nothing else of value). With no same-name StopPlace the favorite is left
 * alone: it keeps showing its saved name, without departures.
 */
@Singleton
class FavoriteRekeyer @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
    private val favoriteDao: FavoriteDao,
    private val recentSearchDao: RecentSearchDao,
) {
    suspend fun run() {
        val index = networkRepository.index()
        val favorites = favoriteDao.getAll().filter { index.stop(it.stopId) == null }
        val updatedFavorites = favorites.mapNotNull { fav -> rekeyFavorite(fav, candidatesNamed(fav.stopName, index)) }
        if (updatedFavorites.isNotEmpty()) favoriteDao.updateAll(updatedFavorites)

        val recents = recentSearchDao.getAll().filter { it.stopId != null && index.stop(it.stopId) == null }
        val updatedRecents = recents.mapNotNull { entry ->
            candidatesNamed(entry.query, index).firstOrNull()?.let { entry.copy(stopId = it.stopPlaceId) }
        }
        if (updatedRecents.isNotEmpty()) recentSearchDao.updateAll(updatedRecents)
    }

    private fun candidatesNamed(name: String, index: NetworkIndex): List<RekeyCandidate> {
        val key = normalizeForMatch(name)
        val network = index.network
        return network.stopPlaces.indices
            .filter { normalizeForMatch(network.stopPlaces[it].name) == key }
            .map { sp -> network.candidate(sp) }
    }
}

/** A StopPlace a retired id may move to, with what's needed to pick the right one. */
internal data class RekeyCandidate(
    val stopPlaceId: String,
    val name: String,
    /** Line short name -> boardable direction codes ("0"/"1"). */
    val directionsByLine: Map<String, Set<String>>,
)

private fun TransitNetwork.candidate(sp: Int): RekeyCandidate = RekeyCandidate(
    stopPlaceId = stopPlaces[sp].id,
    name = stopPlaces[sp].name,
    directionsByLine = servedRoutes(sp).associate { served ->
        routes[served.route].shortName to served.directions.map { it.toString() }.toSet()
    },
)

/**
 * The favorite re-keyed onto the best of [candidates], or null when there is none. A retired stop can
 * have several successors (a stop split in two keeps its name on both sides of a crossroads), so:
 *  1. a line favorite goes to the StopPlace serving its line (and direction, when it has one);
 *  2. otherwise to the one still bearing the saved name;
 *  3. otherwise to the first.
 * The first retired id is kept: a favorite re-keyed twice still remembers what it was saved with.
 */
internal fun rekeyFavorite(favorite: FavoriteEntity, candidates: List<RekeyCandidate>): FavoriteEntity? {
    if (candidates.isEmpty()) return null
    val line = favorite.lineShortName
    val byLine = line?.let { l ->
        candidates.firstOrNull { c -> favorite.direction != null && favorite.direction in c.directionsByLine[l].orEmpty() }
            ?: candidates.firstOrNull { c -> l in c.directionsByLine }
    }
    val byName = candidates.firstOrNull { normalizeForMatch(it.name) == normalizeForMatch(favorite.stopName) }
    val chosen = byLine ?: byName ?: candidates.first()
    return favorite.copy(
        stopId = chosen.stopPlaceId,
        stopName = chosen.name,
        legacyStopId = favorite.legacyStopId ?: favorite.stopId,
    )
}

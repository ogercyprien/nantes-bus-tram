package com.bustramnantes.app.domain.usecase.search

import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.StopRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * A single stop search result row, joined with the [Line]s it serves (for [Line] badge
 * rendering, "each result row: stop name + line badges") and whether it is
 * currently favorited (for the "Favorites always appear at the top" pin).
 */
data class StopSearchResult(
    val stop: Stop,
    val lines: List<Line>,
    val isFavorite: Boolean,
)

/** Grouped output of [SearchStopsUseCase]: favorites pinned separately from the query matches. */
data class StopSearchResults(
    val favorites: List<StopSearchResult>,
    val matches: List<StopSearchResult>,
)

/**
 * Orchestrates the Search screen's Arrêt-mode result list: joins
 * [StopRepository.searchStops] matches with [LineRepository] line data for badge rendering, and
 * surfaces every currently-favorited stop ([FavoriteRepository]) pinned at the top *regardless of
 * the query string* - deduplicated out of [matches] so a favorited stop never appears twice.
 *
 * All three repositories are local-first (Room-backed), so there is no network call, so
 * this is safe to re-run on every keystroke; callers are expected to cancel/replace the previous
 * invocation's coroutine on the next keystroke (manual Job-cancel pattern, not a Flow operator -
 * see `SearchViewModel`).
 */
class SearchStopsUseCase @Inject constructor(
    private val stopRepository: StopRepository,
    private val lineRepository: LineRepository,
    private val favoriteRepository: FavoriteRepository,
) {

    suspend operator fun invoke(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null,
    ): StopSearchResults {
        // A stop's linkedLines are line SHORT NAMES ("10", "C2"), so index the catalog by shortName
        // (Line.id is the full GTFS id `FR_NAOLIB:Line:<short>`).
        val linesByShortName = lineRepository.observeLines().first().associateBy { it.shortName }
        // A favorite may hold any member id of a merged stop: resolve to the displayed stop first.
        val favoriteStops = favoriteRepository.observeFavorites().first()
            .map { it.stopId }
            .distinct()
            .mapNotNull { stopRepository.getStopById(it) }
            .distinctBy { it.id }
        val favoriteIds = favoriteStops.mapTo(HashSet()) { it.id }

        val favorites = favoriteStops.map { stop -> stop.toResult(linesByShortName, isFavorite = true) }

        val matches = stopRepository.searchStops(query, userLat, userLon)
            .filterNot { it.id in favoriteIds }
            .map { stop -> stop.toResult(linesByShortName, isFavorite = false) }

        return StopSearchResults(favorites = favorites, matches = matches)
    }

    private fun Stop.toResult(linesByShortName: Map<String, Line>, isFavorite: Boolean): StopSearchResult =
        toStopSearchResult(linesByShortName, isFavorite)
}

/**
 * Builds a [StopSearchResult] from a [Stop], resolving its [Stop.linkedLines] short names against
 * [linesByShortName] for badge rendering (unresolved lines are simply dropped). Shared by every
 * surface that renders search-result-style rows ([SearchStopsUseCase] and
 * [com.bustramnantes.app.domain.usecase.nearby.GetNearbyStopsUseCase]) so the join isn't duplicated.
 *
 * The catalog MUST be keyed by line **short name** (e.g. "C2", "10"), not [Line.id]: `linkedLines`
 * holds short names, so an id-keyed map would never match and rows would render with no badges.
 */
internal fun Stop.toStopSearchResult(
    linesByShortName: Map<String, Line>,
    isFavorite: Boolean,
): StopSearchResult = StopSearchResult(
    stop = this,
    lines = linkedLines.mapNotNull { linesByShortName[it] },
    isFavorite = isFavorite,
)

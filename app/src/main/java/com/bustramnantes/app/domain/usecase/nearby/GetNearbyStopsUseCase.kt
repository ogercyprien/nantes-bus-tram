package com.bustramnantes.app.domain.usecase.nearby

import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.usecase.search.StopSearchResult
import com.bustramnantes.app.domain.usecase.search.toStopSearchResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Backs the "À proximité" tab: the [limit] stops closest to `(lat, lon)`, each joined with its
 * [com.bustramnantes.app.domain.model.Line] badges and a favorite flag - i.e. the exact
 * [StopSearchResult] shape the Search screen renders, so both surfaces share `StopResultRow` and the
 * badge/favorite join (via [toStopSearchResult]) rather than duplicating that logic.
 *
 * Distance ordering comes from [StopRepository.getNearbyStops]; all three repositories are Room-backed
 * (local-first), so this works offline off the seeded/cached catalog.
 */
class GetNearbyStopsUseCase @Inject constructor(
    private val stopRepository: StopRepository,
    private val lineRepository: LineRepository,
    private val favoriteRepository: FavoriteRepository,
) {

    suspend operator fun invoke(lat: Double, lon: Double, limit: Int = DEFAULT_LIMIT): List<StopSearchResult> {
        val linesByShortName = lineRepository.observeLines().first().associateBy { it.shortName }
        val favoriteStopIds = favoriteRepository.observeFavorites().first()
            .mapTo(mutableSetOf()) { it.stopId }

        return stopRepository.getNearbyStops(lat, lon, limit).map { stop ->
            stop.toStopSearchResult(linesByShortName, isFavorite = stop.memberIds.any { it in favoriteStopIds })
        }
    }

    companion object {
        /** "10 stops closest to me" per the feature spec. */
        const val DEFAULT_LIMIT = 10
    }
}

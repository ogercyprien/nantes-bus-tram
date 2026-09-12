package com.bustramnantes.app.domain.usecase.home

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Per-favorite live departures + resolved line mode for the Home dashboard. */
data class FavoriteDepartures(
    val favorite: Favorite,
    /** Already filtered to [favorite]'s granularity, sorted, capped to 3. */
    val departures: List<Departure>,
    /** `true` when the live fetch for [favorite]'s stop failed (e.g. offline). */
    val isError: Boolean,
    val fetchedAt: Instant,
    /**
     * [TransportMode] for [Favorite.lineId], resolved from the local line cache purely to pick the
     * correct [com.bustramnantes.app.presentation.common.components.LineBadge] shape (circular bus
     * pill vs. taller tram pill) - `null` for [FavoriteType.STOP] favorites (no single
     * line) or if the line cache has no entry for it.
     */
    val lineMode: TransportMode?,
)

/**
 * Assembles everything a Home favorite card needs to render:
 *
 * - Departure boards from [RealtimeBoardRepository] - one shared real-time request for all favorite
 *   stops - filtered down to each favorite's own granularity:
 *     - [FavoriteType.STOP]: next 3 departures across all lines/directions
 *     - [FavoriteType.STOP_LINE]: next 3 departures for [Favorite.lineId], both directions
 *     - [FavoriteType.STOP_LINE_DIRECTION]: next 3 departures for [Favorite.lineId] +
 *       [Favorite.direction] only
 * - The favorite's line [TransportMode] (bus vs. tram), resolved via [LineRepository] since
 *   [Favorite] itself only stores color/short-name/text-color, not the mode needed to pick the
 *   right badge shape.
 *
 * [HomeViewModel] drives its own loop (every 30 s, or as soon as [updates] fires) and calls this as a
 * plain suspend batch, which also makes pull-to-refresh a trivial "call it once more".
 */
class GetFavoritesWithDeparturesUseCase @Inject constructor(
    private val realtime: RealtimeBoardRepository,
    private val lineRepository: LineRepository,
) {
    /** Fires after each real-time fetch - Home re-asks then instead of waiting for its 30 s tick. */
    val updates: Flow<Unit> get() = realtime.updates

    /**
     * @param awaitRealtimeMs when > 0, wait up to that long for a SIRI fetch covering the favorites'
     * stops (the widget refresh, which runs every ~5 min and would otherwise mostly show provisional
     * boards); 0 = return the best boards available right now (Home, which re-asks on [updates]).
     */
    suspend operator fun invoke(favorites: List<Favorite>, awaitRealtimeMs: Long = 0): List<FavoriteDepartures> = coroutineScope {
        val now = Clock.System.now()
        val stopIds = favorites.map { it.stopId }.distinct()
        // One shared request for every favorite stop (SIRI batches all their quays in one call).
        val boards = if (awaitRealtimeMs > 0) {
            realtime.awaitRealtime(stopIds, RealtimePriority.FAVORITES, awaitRealtimeMs)
        } else {
            realtime.boards(stopIds, RealtimePriority.FAVORITES)
        }

        val lineModeById = favorites
            .mapNotNull { it.lineId }
            .distinct()
            .associateWith { lineId -> async { lineRepository.getLineById(lineId)?.mode } }
            .mapValues { (_, deferred) -> deferred.await() }

        favorites.map { favorite ->
            val board = boards[favorite.stopId]
            val filtered = board?.departures
                ?.filter { departure -> matchesFavorite(favorite, departure) }
                ?.sortedBy { it.nextDepartedTime }
                ?.take(MAX_DEPARTURES_PER_CARD)
                .orEmpty()
            FavoriteDepartures(
                favorite = favorite,
                departures = filtered,
                isError = board == null,
                fetchedAt = now,
                lineMode = favorite.lineId?.let { lineModeById[it] },
            )
        }
    }

    private fun matchesFavorite(favorite: Favorite, departure: Departure): Boolean = when (favorite.type) {
        FavoriteType.STOP -> true
        FavoriteType.STOP_LINE -> departure.lineId == favorite.lineId
        FavoriteType.STOP_LINE_DIRECTION ->
            departure.lineId == favorite.lineId && departure.direction.code == favorite.direction
    }

    private companion object {
        const val MAX_DEPARTURES_PER_CARD = 3
    }
}

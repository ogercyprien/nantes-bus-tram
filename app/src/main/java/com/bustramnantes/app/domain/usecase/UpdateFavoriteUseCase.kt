package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.repository.FavoriteRepository
import javax.inject.Inject

/**
 * "Modifier le favori": gives an existing [Favorite] a new granularity / line / direction **in
 * place** - same [Favorite.id] (a home widget bound to it follows), same [Favorite.position] on
 * Home, same stop and [Favorite.addedAt]. Fields are cleared per [FavoriteType] exactly as
 * [AddFavoriteUseCase] does (STOP: no line nor direction; STOP_LINE: no direction).
 */
class UpdateFavoriteUseCase @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) {
    suspend operator fun invoke(
        favorite: Favorite,
        type: FavoriteType,
        lineId: String?,
        lineShortName: String?,
        lineColor: String?,
        lineTextColor: String?,
        direction: String?,
        directionName: String?,
    ): Favorite {
        val updated = favorite.reconfigured(type, lineId, lineShortName, lineColor, lineTextColor, direction, directionName)
        favoriteRepository.updateFavorite(updated)
        return updated
    }
}

/** [this] favorite with a new configuration, everything else (id, stop, position, dates) kept. */
fun Favorite.reconfigured(
    type: FavoriteType,
    lineId: String?,
    lineShortName: String?,
    lineColor: String?,
    lineTextColor: String?,
    direction: String?,
    directionName: String?,
): Favorite {
    val includeLine = type != FavoriteType.STOP
    val includeDirection = type == FavoriteType.STOP_LINE_DIRECTION
    return copy(
        type = type,
        lineId = lineId.takeIf { includeLine },
        lineShortName = lineShortName.takeIf { includeLine },
        lineColor = lineColor.takeIf { includeLine },
        lineTextColor = lineTextColor.takeIf { includeLine },
        direction = direction.takeIf { includeDirection },
        directionName = directionName.takeIf { includeDirection },
    )
}

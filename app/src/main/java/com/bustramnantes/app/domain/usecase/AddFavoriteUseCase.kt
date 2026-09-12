package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.repository.FavoriteRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Builds and persists a new [Favorite] from the raw fields available at the call site (a stop,
 * optionally a focused line/direction), enforcing the field-nullability contract implied by
 * [FavoriteType]:
 * - [FavoriteType.STOP] never carries line/direction fields.
 * - [FavoriteType.STOP_LINE] carries line fields but never a direction.
 * - [FavoriteType.STOP_LINE_DIRECTION] carries both.
 *
 * [Favorite.position] is set to `0` - [FavoriteRepository.addFavorite] is documented to insert at
 * the end of the current ordering regardless of the position supplied, so callers of this use case
 * never need to know the current favorite count.
 */
class AddFavoriteUseCase @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) {
    suspend operator fun invoke(
        type: FavoriteType,
        stopId: String,
        stopName: String,
        lineId: String? = null,
        lineShortName: String? = null,
        lineColor: String? = null,
        lineTextColor: String? = null,
        direction: String? = null,
        directionName: String? = null,
    ) {
        val includeLine = type != FavoriteType.STOP
        val includeDirection = type == FavoriteType.STOP_LINE_DIRECTION

        val favorite = Favorite(
            id = UUID.randomUUID().toString(),
            type = type,
            stopId = stopId,
            stopName = stopName,
            lineId = lineId.takeIf { includeLine },
            lineShortName = lineShortName.takeIf { includeLine },
            lineColor = lineColor.takeIf { includeLine },
            lineTextColor = lineTextColor.takeIf { includeLine },
            direction = direction.takeIf { includeDirection },
            directionName = directionName.takeIf { includeDirection },
            position = 0,
            addedAt = System.currentTimeMillis(),
        )
        favoriteRepository.addFavorite(favorite)
    }
}

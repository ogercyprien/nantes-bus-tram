package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.local.dao.FavoriteDao
import com.bustramnantes.app.data.local.entity.FavoriteEntity
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.repository.FavoriteRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Thin Room-backed CRUD/Flow wrapper around the fully-local `favorites` table. */
@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
) : FavoriteRepository {

    override fun observeFavorites(): Flow<List<Favorite>> =
        favoriteDao.observeAll().map { entities -> entities.map(FavoriteEntity::toDomain) }

    override suspend fun getFavoriteById(id: String): Favorite? = favoriteDao.getById(id)?.toDomain()

    override suspend fun addFavorite(favorite: Favorite) {
        // Position is computed + inserted atomically in one transaction (see FavoriteDao.insertAtEnd)
        // so concurrent adds can't read the same max position and collide.
        favoriteDao.insertAtEnd(favorite.toEntity())
    }

    override suspend fun updateFavorite(favorite: Favorite) = favoriteDao.update(favorite.toEntity())

    // Positions are a plain sort key (no uniqueness), so the old slot can be taken back as is.
    override suspend fun restoreFavorite(favorite: Favorite) = favoriteDao.insert(favorite.toEntity())

    override suspend fun removeFavorite(favoriteId: String) = favoriteDao.delete(favoriteId)

    override suspend fun reorderFavorites(orderedFavoriteIds: List<String>) =
        favoriteDao.reorder(orderedFavoriteIds)

    override suspend fun clearAllFavorites() = favoriteDao.clearAll()
}

private fun FavoriteEntity.toDomain(): Favorite = Favorite(
    id = id,
    type = FavoriteType.valueOf(type),
    stopId = stopId,
    stopName = stopName,
    lineId = lineId,
    lineShortName = lineShortName,
    lineColor = lineColor,
    lineTextColor = lineTextColor,
    direction = direction,
    directionName = directionName,
    position = position,
    addedAt = addedAt,
    legacyStopId = legacyStopId,
)

private fun Favorite.toEntity(): FavoriteEntity = FavoriteEntity(
    id = id,
    type = type.name,
    stopId = stopId,
    stopName = stopName,
    lineId = lineId,
    lineShortName = lineShortName,
    lineColor = lineColor,
    lineTextColor = lineTextColor,
    direction = direction,
    directionName = directionName,
    position = position,
    addedAt = addedAt,
    legacyStopId = legacyStopId,
)

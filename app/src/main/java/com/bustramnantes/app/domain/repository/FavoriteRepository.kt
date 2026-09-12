package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.Favorite
import kotlinx.coroutines.flow.Flow

/** CRUD access to the fully-local `favorites` table. */
interface FavoriteRepository {

    /** Ordered by [Favorite.position]; drives the Home dashboard. */
    fun observeFavorites(): Flow<List<Favorite>>

    suspend fun getFavoriteById(id: String): Favorite?

    /** Inserts [favorite] at the end of the current ordering (highest [Favorite.position] + 1). */
    suspend fun addFavorite(favorite: Favorite)

    suspend fun removeFavorite(favoriteId: String)

    /**
     * Persists a new drag-and-drop order ("Réorganiser"). [orderedFavoriteIds] is
     * the full list of favorite IDs in their new display order.
     */
    suspend fun reorderFavorites(orderedFavoriteIds: List<String>)

    /** "Tout supprimer" in Settings. */
    suspend fun clearAllFavorites()
}

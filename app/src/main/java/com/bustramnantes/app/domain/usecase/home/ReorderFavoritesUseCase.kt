package com.bustramnantes.app.domain.usecase.home

import com.bustramnantes.app.domain.repository.FavoriteRepository
import javax.inject.Inject

/** Persists a new favorite display order ("Réorganiser"). */
class ReorderFavoritesUseCase @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) {
    suspend operator fun invoke(orderedFavoriteIds: List<String>) {
        favoriteRepository.reorderFavorites(orderedFavoriteIds)
    }
}

package com.bustramnantes.app.domain.usecase.home

import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.repository.FavoriteRepository
import javax.inject.Inject

/**
 * Removes a favorite from the Home dashboard with snackbar-undo support ("Supprimer").
 * [remove] performs the deletion; [undo] re-adds the exact same [Favorite] if the
 * user taps "Annuler" on the snackbar before it is dismissed.
 *
 * Note: [FavoriteRepository.addFavorite] re-inserts at the end of the current ordering (per its
 * own contract), so an undone favorite may reappear at a different position than it had before
 * deletion rather than its exact original slot - acceptable for the undo affordance, which only
 * requires the favorite itself to come back.
 */
class RemoveFavoriteUseCase @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) {
    suspend fun remove(favorite: Favorite) {
        favoriteRepository.removeFavorite(favorite.id)
    }

    suspend fun undo(favorite: Favorite) {
        favoriteRepository.addFavorite(favorite)
    }
}

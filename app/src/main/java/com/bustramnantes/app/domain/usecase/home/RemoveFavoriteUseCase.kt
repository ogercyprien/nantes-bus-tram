package com.bustramnantes.app.domain.usecase.home

import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.repository.FavoriteRepository
import javax.inject.Inject

/**
 * Removes a favorite with snackbar-undo support (Home "Supprimer", Stop Detail "Retirer").
 * [remove] performs the deletion; [undo] puts the exact same [Favorite] back - same id, same
 * position on Home ([FavoriteRepository.restoreFavorite]) - if the user taps "Annuler" on the
 * snackbar before it is dismissed.
 */
class RemoveFavoriteUseCase @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) {
    suspend fun remove(favorite: Favorite) {
        favoriteRepository.removeFavorite(favorite.id)
    }

    suspend fun undo(favorite: Favorite) {
        favoriteRepository.restoreFavorite(favorite)
    }
}

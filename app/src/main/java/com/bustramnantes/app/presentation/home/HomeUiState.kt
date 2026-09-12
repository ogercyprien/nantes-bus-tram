package com.bustramnantes.app.presentation.home

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.Instant

/**
 * Home dashboard state, exposed by [HomeViewModel] as a single `StateFlow`.
 */
sealed interface HomeUiState {

    /** Initial state, before the first favorites emission has been observed. */
    data object Loading : HomeUiState

    /**
     * No favorites saved yet - drives the
     * [com.bustramnantes.app.presentation.common.components.EmptyState] CTA.
     */
    data object Empty : HomeUiState

    data class Content(
        /** One entry per favorite, in display order (`Favorite.position`). */
        val cards: List<FavoriteCardUiState>,
        /** `true` while a manual (pull-to-refresh) refresh is in flight. */
        val isRefreshing: Boolean = false,
        /**
         * `true` while "Réorganiser" mode is active. Cards render up/down move
         * controls instead of being long-press-able while this is `true` (see HomeScreen.kt for
         * why up/down controls were chosen over a full pointer-drag gesture).
         */
        val isReorderMode: Boolean = false,
        /** Id of the favorite whose long-press context menu is currently open, if any. */
        val menuOpenFavoriteId: String? = null,
    ) : HomeUiState
}

/**
 * Disruption notices in force (or starting soon) on the whole network, for the top-bar
 * "Perturbations" action. Kept out of [HomeUiState] so it shows in every state (even with no
 * favorite).
 */
data class HomeDisruptionsUiState(val currentCount: Int = 0)

/** Per-card departures state (card layout). */
data class FavoriteCardUiState(
    val favorite: Favorite,
    /** Already filtered/sorted/capped to this favorite's granularity - up to 3 entries. */
    val departures: List<Departure> = emptyList(),
    /** `true` when the last live fetch for this favorite's stop failed (e.g. offline). */
    val isError: Boolean = false,
    val lastUpdated: Instant? = null,
    /** Bus vs. tram, for the header [com.bustramnantes.app.presentation.common.components.LineBadge] shape. */
    val lineMode: TransportMode? = null,
    /** Notices relevant to this favorite (see `forFavorite`), most relevant first. */
    val disruptions: List<Disruption> = emptyList(),
    /** Lines with one of [disruptions] in force now - an amber dot on their badges. */
    val disruptedLineIds: Set<String> = emptySet(),
)

package com.bustramnantes.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.util.current
import com.bustramnantes.app.domain.util.forFavorite
import kotlinx.datetime.Clock
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.usecase.TIMETABLE_REFRESH_INTERVAL_MS
import com.bustramnantes.app.domain.usecase.home.GetFavoritesWithDeparturesUseCase
import com.bustramnantes.app.domain.usecase.home.RemoveFavoriteUseCase
import com.bustramnantes.app.domain.usecase.home.ReorderFavoritesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Pull-to-refresh waits at most this long for fresh real-time data (the shared slot is ≤ 31 s away). */
private const val PULL_REFRESH_AWAIT_MS = 10_000L

/** The pull-to-refresh spinner stays at least this long, so it is always seen to start and stop. */
private const val PULL_REFRESH_MIN_VISIBLE_MS = 600L

/** User intents for the Home dashboard - the single entry point is [HomeViewModel.onEvent]. */
sealed interface HomeEvent {
    /** Screen entered [androidx.lifecycle.Lifecycle.State.RESUMED] - (re)starts the 30s auto-refresh loop. */
    data object ScreenResumed : HomeEvent

    /** Screen left the resumed state - stops the 30s auto-refresh loop. */
    data object ScreenPaused : HomeEvent

    /** Pull-to-refresh - triggers an immediate refresh. */
    data object RefreshRequested : HomeEvent

    /** Empty-state CTA tapped - navigates to the Recherche tab. */
    data object EmptyStateCtaClicked : HomeEvent

    /** Plain tap on a card - opens Stop Detail for that favorite's stop. */
    data class CardClicked(val favoriteId: String) : HomeEvent

    /** Long-press on a card - opens its context menu. */
    data class CardLongPressed(val favoriteId: String) : HomeEvent

    /** Context menu dismissed without picking an action. */
    data object CardMenuDismissed : HomeEvent

    /** "Voir l'arrêt" menu item. */
    data class ViewStopClicked(val favoriteId: String) : HomeEvent

    /** "Modifier" menu item - opens the favorite's stop with its editor up. */
    data class EditFavoriteClicked(val favoriteId: String) : HomeEvent

    /** "Supprimer" menu item. */
    data class DeleteFavoriteClicked(val favoriteId: String) : HomeEvent

    /** "Annuler" tapped on the post-delete snackbar. */
    data object UndoDeleteClicked : HomeEvent

    /** "Réorganiser" menu item - enters reorder mode for every card. */
    data object ReorderModeEntered : HomeEvent

    /** "Terminé" tapped while reordering - leaves reorder mode. */
    data object ReorderModeExited : HomeEvent

    /** Up-arrow tapped on a card while reordering. */
    data class MoveFavoriteUp(val favoriteId: String) : HomeEvent

    /** Down-arrow tapped on a card while reordering. */
    data class MoveFavoriteDown(val favoriteId: String) : HomeEvent
}

/** One-off Home effects (navigation, snackbars) delivered via [HomeViewModel.effects]. */
sealed interface HomeEffect {
    data object NavigateToSearch : HomeEffect

    data class NavigateToStopDetail(
        val stopId: String,
        val lineId: String?,
        val direction: String?,
        /** Set by "Modifier": the Stop Detail sheet opens on this favorite's editor. */
        val editFavoriteId: String? = null,
    ) : HomeEffect

    data class ShowUndoSnackbar(val stopName: String) : HomeEffect
}

/**
 * MVI ViewModel for the Home dashboard.
 *
 * Auto-refresh: callers (see `HomeScreen.kt`) forward [HomeEvent.ScreenResumed] /
 * [HomeEvent.ScreenPaused] from the host `Lifecycle`, which starts/stops a 30-second polling loop.
 * Timetable data is always fetched live through [GetFavoritesWithDeparturesUseCase] - never
 * cached.
 *
 * Reordering: "Réorganiser" keeps an optimistic local order ([reorderWorkingOrder]) so up/down
 * moves feel instant, persisting each move immediately via [ReorderFavoritesUseCase] (a local Room
 * write, cheap enough to fire on every move rather than batching until "Terminé").
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val getFavoritesWithDeparturesUseCase: GetFavoritesWithDeparturesUseCase,
    private val removeFavoriteUseCase: RemoveFavoriteUseCase,
    private val reorderFavoritesUseCase: ReorderFavoritesUseCase,
    private val disruptionRepository: DisruptionRepository,
    private val stopRepository: StopRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _disruptionsState = MutableStateFlow(HomeDisruptionsUiState())
    val disruptionsState: StateFlow<HomeDisruptionsUiState> = _disruptionsState.asStateFlow()

    /** Latest notices from [DisruptionRepository]. */
    private var disruptions: List<Disruption> = emptyList()

    /** Favorite stop id -> its display stop (member ids, linked lines), for notice relevance. */
    private val stopsById = HashMap<String, Stop>()

    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    /** Authoritative favorite list + order, as last observed from [FavoriteRepository.observeFavorites]. */
    private var favorites: List<Favorite> = emptyList()

    /** Local optimistic order while reorder mode is active; `null` when not reordering. */
    private var reorderWorkingOrder: List<Favorite>? = null

    /** Latest departures per favorite id, preserved across reorders/menu toggles between refreshes. */
    private var departuresByFavoriteId: Map<String, FavoriteCardUiState> = emptyMap()

    /** Favorite captured by the most recent "Supprimer", kept until undone or superseded. */
    private var lastRemovedFavorite: Favorite? = null

    private var refreshJob: Job? = null

    /** A pull-to-refresh is running (drives [HomeUiState.Content.isRefreshing]). */
    private var isManualRefreshing = false
    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            favoriteRepository.observeFavorites().collect { latest -> onFavoritesChanged(latest) }
        }
        viewModelScope.launch {
            disruptionRepository.disruptions.collect { latest ->
                disruptions = latest
                _disruptionsState.value = HomeDisruptionsUiState(currentCount = latest.current(Clock.System.now()).size)
                if (_uiState.value is HomeUiState.Content) publishContent()
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.ScreenResumed -> startPolling()
            HomeEvent.ScreenPaused -> stopPolling()
            HomeEvent.RefreshRequested -> refresh(showRefreshIndicator = true)
            HomeEvent.EmptyStateCtaClicked -> emitEffect(HomeEffect.NavigateToSearch)
            is HomeEvent.CardClicked -> openStopDetail(event.favoriteId)
            is HomeEvent.CardLongPressed -> updateContent { it.copy(menuOpenFavoriteId = event.favoriteId) }
            HomeEvent.CardMenuDismissed -> updateContent { it.copy(menuOpenFavoriteId = null) }
            is HomeEvent.ViewStopClicked -> {
                updateContent { it.copy(menuOpenFavoriteId = null) }
                openStopDetail(event.favoriteId)
            }
            is HomeEvent.EditFavoriteClicked -> {
                updateContent { it.copy(menuOpenFavoriteId = null) }
                openStopDetail(event.favoriteId, edit = true)
            }
            is HomeEvent.DeleteFavoriteClicked -> deleteFavorite(event.favoriteId)
            HomeEvent.UndoDeleteClicked -> undoDelete()
            HomeEvent.ReorderModeEntered -> enterReorderMode()
            HomeEvent.ReorderModeExited -> exitReorderMode()
            is HomeEvent.MoveFavoriteUp -> moveFavorite(event.favoriteId, offset = -1)
            is HomeEvent.MoveFavoriteDown -> moveFavorite(event.favoriteId, offset = 1)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    private fun onFavoritesChanged(latest: List<Favorite>) {
        val previousIds = favorites.map { it.id }.toSet()
        favorites = latest

        val workingOrder = reorderWorkingOrder
        if (workingOrder != null) {
            reorderWorkingOrder = latest.sortedBy { fav ->
                workingOrder.indexOfFirst { it.id == fav.id }.let { idx -> if (idx == -1) Int.MAX_VALUE else idx }
            }
        }

        viewModelScope.launch {
            var added = false
            for (favorite in latest) {
                if (favorite.stopId in stopsById) continue
                stopRepository.getStopById(favorite.stopId)?.let { stopsById[favorite.stopId] = it; added = true }
            }
            if (added && _uiState.value is HomeUiState.Content) publishContent()
        }

        when {
            latest.isEmpty() -> {
                reorderWorkingOrder = null
                departuresByFavoriteId = emptyMap()
                _uiState.value = HomeUiState.Empty
            }
            // Favorite set actually changed (added/removed) - fetch departures for the new set.
            latest.map { it.id }.toSet() != previousIds -> refresh(showRefreshIndicator = false)
            // Pure reorder (or an identical re-emission) - republish with already-cached departures.
            else -> publishContent()
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            launch { disruptionRepository.refreshIfStale() }
            while (true) {
                // Re-ask as soon as a real-time fetch lands (a provisional board turns into the SIRI
                // one within a slot), else every 30 s - which also keeps the favorites' interest alive.
                withTimeoutOrNull(TIMETABLE_REFRESH_INTERVAL_MS) { getFavoritesWithDeparturesUseCase.updates.first() }
                refresh(showRefreshIndicator = false)
                launch { disruptionRepository.refreshIfStale() }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Reloads the cards. [showRefreshIndicator] = the pull-to-refresh gesture: a real refresh that waits
     * (up to [PULL_REFRESH_AWAIT_MS]) for fresh real-time data, with the spinner shown at least
     * [PULL_REFRESH_MIN_VISIBLE_MS]. That minimum matters: boards usually come from the real-time cache
     * instantly, and a spinner switched on and off within one frame is never seen as "refreshing" by
     * Material 3's PullToRefreshBox, which then stays stuck at its threshold. A background refresh
     * never cancels a pull-to-refresh in progress.
     */
    private fun refresh(showRefreshIndicator: Boolean) {
        if (favorites.isEmpty()) return
        if (!showRefreshIndicator && isManualRefreshing) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            if (showRefreshIndicator) {
                isManualRefreshing = true
                updateContent { it.copy(isRefreshing = true) }
            }
            try {
                loadCards(awaitRealtimeMs = if (showRefreshIndicator) PULL_REFRESH_AWAIT_MS else 0)
                if (showRefreshIndicator) delay(PULL_REFRESH_MIN_VISIBLE_MS - (System.currentTimeMillis() - started))
            } finally {
                if (showRefreshIndicator) isManualRefreshing = false
                publishContent()
            }
        }
    }

    private suspend fun loadCards(awaitRealtimeMs: Long) {
            val results = getFavoritesWithDeparturesUseCase(favorites, awaitRealtimeMs)
            departuresByFavoriteId = results.associate { result ->
                result.favorite.id to FavoriteCardUiState(
                    favorite = result.favorite,
                    departures = result.departures,
                    isError = result.isError,
                    lastUpdated = result.fetchedAt,
                    lineMode = result.lineMode,
                )
            }
    }

    /** Republishes the cards; the pull-to-refresh spinner follows [isManualRefreshing] only. */
    private fun publishContent() {
        val isRefreshing = isManualRefreshing
        val order = reorderWorkingOrder ?: favorites
        val previousMenuOpenId = (_uiState.value as? HomeUiState.Content)?.menuOpenFavoriteId
        val now = Clock.System.now()
        val cards = order.map { favorite ->
            val card = departuresByFavoriteId[favorite.id]?.copy(favorite = favorite)
                ?: FavoriteCardUiState(favorite = favorite)
            val stop = stopsById[favorite.stopId]
            val served = stop?.linkedLines?.map { Line.idFor(it) }.orEmpty()
            val relevant = disruptions.forFavorite(favorite, stop?.memberIds ?: listOf(favorite.stopId), served, now)
            card.copy(
                disruptions = relevant,
                disruptedLineIds = relevant.filter { it.isActiveAt(now) }
                    .flatMap { d -> if (d.lineIds.isEmpty()) listOfNotNull(favorite.lineId) + served else d.lineIds }
                    .toSet(),
            )
        }
        _uiState.value = HomeUiState.Content(
            cards = cards,
            isRefreshing = isRefreshing,
            isReorderMode = reorderWorkingOrder != null,
            menuOpenFavoriteId = previousMenuOpenId,
        )
    }

    private inline fun updateContent(transform: (HomeUiState.Content) -> HomeUiState.Content) {
        val current = _uiState.value
        if (current is HomeUiState.Content) {
            _uiState.value = transform(current)
        }
    }

    private fun openStopDetail(favoriteId: String, edit: Boolean = false) {
        val favorite = favorites.find { it.id == favoriteId } ?: return
        emitEffect(
            HomeEffect.NavigateToStopDetail(
                stopId = favorite.stopId,
                lineId = favorite.lineId,
                direction = favorite.direction,
                editFavoriteId = favorite.id.takeIf { edit },
            ),
        )
    }

    private fun deleteFavorite(favoriteId: String) {
        val favorite = favorites.find { it.id == favoriteId } ?: return
        updateContent { it.copy(menuOpenFavoriteId = null) }
        lastRemovedFavorite = favorite
        viewModelScope.launch {
            removeFavoriteUseCase.remove(favorite)
            emitEffect(HomeEffect.ShowUndoSnackbar(favorite.stopName))
        }
    }

    private fun undoDelete() {
        val favorite = lastRemovedFavorite ?: return
        lastRemovedFavorite = null
        viewModelScope.launch {
            removeFavoriteUseCase.undo(favorite)
        }
    }

    private fun enterReorderMode() {
        reorderWorkingOrder = favorites
        updateContent { it.copy(menuOpenFavoriteId = null) }
        publishContent()
    }

    private fun exitReorderMode() {
        reorderWorkingOrder = null
        publishContent()
    }

    private fun moveFavorite(favoriteId: String, offset: Int) {
        val order = reorderWorkingOrder ?: return
        val index = order.indexOfFirst { it.id == favoriteId }
        val targetIndex = index + offset
        if (index == -1 || targetIndex !in order.indices) return
        val newOrder = order.toMutableList().apply {
            val moved = removeAt(index)
            add(targetIndex, moved)
        }
        reorderWorkingOrder = newOrder
        publishContent()
        viewModelScope.launch {
            reorderFavoritesUseCase(newOrder.map { it.id })
        }
    }

    private fun emitEffect(effect: HomeEffect) {
        viewModelScope.launch { _effects.emit(effect) }
    }
}

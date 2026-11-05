package com.bustramnantes.app.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.bustramnantes.app.data.local.AppPreferencesDataStore
import com.bustramnantes.app.data.network.LineMapRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.StopRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [MapScreen]. Owns only plain-Kotlin state - the MapLibre `MapView`/
 * `MapLibreMap`/camera are owned by the Compose layer since they are platform View objects tied to
 * lifecycle, not app state that survives configuration in a meaningful way here (default camera is
 * cheaply recomputed).
 *
 * Reuses the exact stop-search contract (fuzzy/prefix match via
 * [StopRepository.searchStops], favorites pinned to the top, line badges resolved from
 * [LineRepository]) without importing anything from the `search` feature package, per the task's
 * "keep this module self-contained" instruction.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val stopRepository: StopRepository,
    private val lineRepository: LineRepository,
    private val favoriteRepository: FavoriteRepository,
    lineMapRepository: LineMapRepository,
    preferences: AppPreferencesDataStore,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    // Keyed by line *short name* (e.g. "C8"), not Line.id: `Stop.linkedLines` holds short names.
    // Matches SearchStopsUseCase's indexing.
    private var linesByShortName: Map<String, Line> = emptyMap()
    private var favoriteStopIds: Set<String> = emptySet()

    /** Last known user fix, reported by the Compose layer once MapLibre's LocationComponent has one. */
    private var userLat: Double? = null
    private var userLon: Double? = null

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            stopRepository.observeStops().collect { stops ->
                _uiState.update { it.copy(stops = stops, isLoadingStops = false) }
            }
        }
        viewModelScope.launch {
            lineMapRepository.lineMaps.collect { maps -> _uiState.update { it.copy(lineMaps = maps) } }
        }
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            viewModelScope.launch {
                preferences.officialLineTiles.collect { official -> _uiState.update { it.copy(officialLineTiles = official) } }
            }
        }
        viewModelScope.launch {
            lineRepository.observeLines().collect { lines ->
                linesByShortName = lines.associateBy { it.shortName }
            }
        }
        viewModelScope.launch {
            favoriteRepository.observeFavorites().collect { favorites ->
                favoriteStopIds = favorites.mapTo(mutableSetOf()) { it.stopId }
            }
        }
    }

    /**
     * Prefills the map search bar with the name of the stop handed in by "Voir sur la carte".
     *
     * The stop id is delivered on the Map back-stack entry's *own* `SavedStateHandle` (see
     * [com.bustramnantes.app.presentation.common.navigation.showStopOnMap]) and read by [MapScreen]
     * from that entry - NOT from the `SavedStateHandle` Hilt injects here, which is a distinct
     * instance and never sees that write. [MapScreen] therefore forwards it explicitly so the search
     * bar reflects the preselected stop; the actual highlight/camera is driven by `selectStopOnMap`.
     */
    fun onPreselectStop(stopId: String) {
        viewModelScope.launch {
            val name = stopRepository.getStopById(stopId)?.name ?: return@launch
            _uiState.update { it.copy(preselectStopId = stopId, searchQuery = name) }
        }
    }

    fun onEvent(event: MapEvent) {
        when (event) {
            is MapEvent.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = event.query) }
                scheduleSearch(event.query)
            }

            is MapEvent.SearchExpandedChanged -> {
                _uiState.update { it.copy(isSearchExpanded = event.expanded) }
            }

            is MapEvent.UserLocationKnown -> {
                userLat = event.lat
                userLon = event.lon
            }
        }
    }

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.update { it.copy(isSearching = true) }
            val results = runCatching { stopRepository.searchStops(query, userLat, userLon) }
                .getOrDefault(emptyList())
                .map { stop ->
                    StopSearchResult(
                        stop = stop,
                        lines = stop.linkedLines.mapNotNull { shortName -> linesByShortName[shortName] },
                        isFavorite = stop.memberIds.any { it in favoriteStopIds },
                    )
                }
                // Stable sort: favorites first, otherwise preserves the repository's own
                // proximity/alphabetical ordering.
                .sortedByDescending { it.isFavorite }
            _uiState.update { it.copy(searchResults = results, isSearching = false) }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}

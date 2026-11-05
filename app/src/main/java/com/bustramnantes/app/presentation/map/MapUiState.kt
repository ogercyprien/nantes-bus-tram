package com.bustramnantes.app.presentation.map

import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.transit.lines.LineMapSet

/**
 * A stop search result row, paired with the resolved [Line] badges for [Stop.linkedLines]
 * (stop name + line badges). Lines that failed to resolve (e.g. cache not
 * warmed yet) are simply omitted from [lines].
 */
data class StopSearchResult(
    val stop: Stop,
    val lines: List<Line>,
    val isFavorite: Boolean,
)

/**
 * UI state for [MapScreen]. Deliberately free of any MapLibre/Android types (LatLng, Style, …) -
 * those are owned by the Compose/View layer inside `MapScreen`/`MapStyle`, keeping this state
 * plain-Kotlin and easy to reason about.
 */
data class MapUiState(
    val stops: List<Stop> = emptyList(),
    val isLoadingStops: Boolean = true,
    val searchQuery: String = "",
    val isSearchExpanded: Boolean = false,
    val isSearching: Boolean = false,
    val searchResults: List<StopSearchResult> = emptyList(),
    /** Stop id to preselect (highlight ring + center camera), set by "Voir sur la carte". */
    val preselectStopId: String? = null,
    /** Our line map (one layout per zoom) for the network in use; null until read at startup. */
    val lineMaps: LineMapSet? = null,
    /** Debug builds only: draw the official raster tiles instead of [lineMaps] (Settings › Débogage). */
    val officialLineTiles: Boolean = false,
) {
    /** Whether the "no results" empty state should be shown under the search bar. */
    val showNoResults: Boolean
        get() = isSearchExpanded && searchQuery.isNotBlank() && !isSearching && searchResults.isEmpty()
}

/** User intents handled by [MapViewModel]. */
sealed interface MapEvent {
    data class SearchQueryChanged(val query: String) : MapEvent
    data class SearchExpandedChanged(val expanded: Boolean) : MapEvent

    /**
     * Reported once the map/UI layer obtains a user location fix (from MapLibre's
     * `LocationComponent`), so subsequent [com.bustramnantes.app.domain.repository.StopRepository.searchStops]
     * calls can sort by proximity.
     */
    data class UserLocationKnown(val lat: Double, val lon: Double) : MapEvent
}

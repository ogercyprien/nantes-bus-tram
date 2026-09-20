package com.bustramnantes.app.presentation.nearby

import com.bustramnantes.app.domain.usecase.search.StopSearchResult

/**
 * UI state for [NearbyStopsScreen] ("À proximité"): the stops closest to the user, rendered as the
 * same [StopSearchResult] rows the Search screen uses.
 */
data class NearbyStopsUiState(
    val isLoading: Boolean = true,
    /** `true` while a *manual* fresh-location refresh is running (drives the pull-to-refresh spinner). */
    val isRefreshing: Boolean = false,
    val stops: List<StopSearchResult> = emptyList(),
    /** Whether the location permission is currently granted (drives the "autoriser" empty state). */
    val hasLocationPermission: Boolean = false,
    /** `true` when permission is granted but no location fix is available yet (drives a retry state). */
    val locationUnavailable: Boolean = false,
)

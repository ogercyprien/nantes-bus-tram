package com.bustramnantes.app.presentation.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.usecase.nearby.GetNearbyStopsUseCase
import com.bustramnantes.app.presentation.search.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [NearbyStopsScreen] ("À proximité"): resolves the user's location via [LocationProvider]
 * and asks [GetNearbyStopsUseCase] for the closest stops, reusing the exact
 * [com.bustramnantes.app.domain.usecase.search.StopSearchResult] rows the Search screen renders.
 *
 * Two location strategies, per the product ask:
 *  - [refresh] (called on each resume) uses the **last-known** fix - instant, but can be stale right
 *    after the app opens. This is deliberately kept so the list shows *something* immediately.
 *  - [refreshFresh] (pull-to-refresh / the app-bar refresh icon) actively requests the **current**
 *    fix, so the user can force the list to reflect where they actually are now.
 */
@HiltViewModel
class NearbyStopsViewModel @Inject constructor(
    private val userLocationProvider: LocationProvider,
    private val getNearbyStopsUseCase: GetNearbyStopsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyStopsUiState())
    val uiState: StateFlow<NearbyStopsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Resolve the real permission state synchronously so the first frame never shows the
        // "autoriser la localisation" state to a user who has already granted it (it would flash
        // there until the first refresh completed). Spin only if we're actually going to load.
        val granted = userLocationProvider.hasLocationPermission()
        _uiState.value = NearbyStopsUiState(hasLocationPermission = granted, isLoading = granted)
    }

    /** Called by the screen after a runtime-permission prompt resolves. */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            refresh()
        } else {
            _uiState.update {
                it.copy(hasLocationPermission = false, isLoading = false, isRefreshing = false, stops = emptyList())
            }
        }
    }

    /**
     * Reloads from the last-known fix (instant, possibly stale). Used on every resume - but only to
     * *populate* an empty list (see [load]); it never overwrites a list that's already shown.
     */
    fun refresh() = load(fresh = false)

    /** Reloads from an actively-requested current fix. Used by pull-to-refresh / the refresh icon. */
    fun refreshFresh() = load(fresh = true)

    private fun load(fresh: Boolean) {
        val hasPermission = userLocationProvider.hasLocationPermission()
        if (!hasPermission) {
            _uiState.update {
                it.copy(hasLocationPermission = false, isLoading = false, isRefreshing = false, stops = emptyList())
            }
            return
        }
        // The non-fresh (last-known) reload fires on every resume - including when the Stop Detail
        // dialog opened from this tab is dismissed (dismissing it resumes this back-stack entry). Once
        // a list is already shown it must NOT be reloaded from the last-known fix: that fix is a cached
        // position that can still point at the user's *old* location, so it would silently revert a
        // list the user just forced to their current position via the refresh action (pull-to-refresh /
        // the app-bar icon). So a last-known reload only does work for the initial population; after
        // that, location changes are reflected only by the user-initiated fresh refresh.
        if (!fresh && _uiState.value.stops.isNotEmpty()) {
            _uiState.update { it.copy(hasLocationPermission = true, isLoading = false) }
            return
        }
        _uiState.update {
            it.copy(
                hasLocationPermission = true,
                // Pull-to-refresh indicator: only when a list is already visible to pull on. With
                // nothing shown yet (initial load, or retry from the "unavailable" state) we instead
                // show the full-screen spinner below so the action always has visible feedback.
                isRefreshing = fresh && it.stops.isNotEmpty(),
                isLoading = it.stops.isEmpty(),
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // The fresh-fix path (getCurrentLocation, used by pull-to-refresh) can throw - e.g.
                // LocationManagerCompat.getCurrentLocation raises IllegalArgumentException for a
                // provider the device doesn't actually expose. Without this guard the coroutine would
                // die here before the final update below, leaving isRefreshing = true forever (the
                // pull-to-refresh spinner then spins indefinitely). Any failure resolving a fix is just
                // "position unavailable", so surface that and always clear the spinner.
                val location = if (fresh) {
                    userLocationProvider.getCurrentLocation()
                } else {
                    userLocationProvider.getLastKnownLocation()
                }
                if (location == null) {
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, locationUnavailable = true, stops = emptyList())
                    }
                    return@launch
                }
                val results = runCatching { getNearbyStopsUseCase(location.first, location.second) }
                    .getOrDefault(emptyList())
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, locationUnavailable = false, stops = results)
                }
            } catch (e: CancellationException) {
                // A newer load() superseded this one; it already owns the state - don't touch it.
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, locationUnavailable = true)
                }
            }
        }
    }
}

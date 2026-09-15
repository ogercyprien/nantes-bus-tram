package com.bustramnantes.app.presentation.search

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.presentation.common.components.OfflineAwareContent
import com.bustramnantes.app.presentation.search.components.SearchModeSegmentedControl

/**
 * Search screen entry point: stop search, address search, and the itinerary
 * two-field input UI. Journey results are a separate screen - [onNavigate] is called with the
 * `itinerary_results` route built by the ViewModel once both endpoints are resolved.
 *
 * Meant to be hosted directly inside `AppNavGraph`'s `composable(Screen.Search.ROUTE_PATTERN, ...)`
 * block, e.g. `SearchScreen(onNavigate = navController::navigate)`. Every destination [onNavigate]
 * is called with is a concrete route string built via `Screen.*.createRoute(...)` (or, for the
 * Adresse-mode Stop Detail handoff, [addressStopDetailRoute] - see that file's doc comment for the
 * NAV_CONTRACT extension this relies on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigate: (route: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // First composition: if location is already granted, kick an immediate proximity research. When
    // it isn't, we intentionally do NOT prompt here - the one-time app-level welcome dialog (see
    // MainActivity) owns the first-run explanation + permission request; the Nearby tab and Map offer
    // their own opt-in afterwards. Search works fine without location (results fall back to alphabetical).
    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) viewModel.onLocationPermissionResult(true)
    }

    // Re-resolve the current location every time the tab is (re)opened so the default proximity-sorted
    // stop list follows the user rather than sticking to the location known when this VM was created.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onScreenResumed()
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Recherche") }) },
    ) { paddingValues ->
        // Offline banner on every screen.
        OfflineAwareContent(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchModeSegmentedControl(
                    selected = uiState.topMode,
                    onSelected = viewModel::onTopModeChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )

                when (uiState.topMode) {
                    SearchTopMode.STOP -> StopAddressModeContent(
                        uiState = uiState,
                        onStopQueryChange = viewModel::onStopQueryChange,
                        onAddressQueryChange = viewModel::onAddressQueryChange,
                        onOpenAddressSearch = viewModel::onOpenAddressSearch,
                        onCloseAddressSearch = viewModel::onCloseAddressSearch,
                        onStopSelected = { result -> onNavigate(viewModel.onStopResultSelected(result)) },
                        onAddressSelected = { address -> onNavigate(viewModel.onAddressResultSelected(address)) },
                        onRecentSearchSelected = { recent -> viewModel.onRecentSearchSelected(recent)?.let(onNavigate) },
                        onDeleteRecentSearch = viewModel::onDeleteRecentSearch,
                        modifier = Modifier.weight(1f),
                    )

                    SearchTopMode.ITINERARY -> ItineraryModeContent(
                        uiState = uiState,
                        onQueryChange = viewModel::onItineraryQueryChange,
                        onFocusChanged = viewModel::onItineraryFieldFocusChanged,
                        onStopSelected = viewModel::onItineraryStopSelected,
                        onAddressSelected = viewModel::onItineraryAddressSelected,
                        onSwap = viewModel::onSwapItineraryFields,
                        onSearch = { viewModel.buildJourneySearchRoute()?.let(onNavigate) },
                        onOpenDepartureTimePicker = viewModel::onOpenDepartureTimePicker,
                        onDismissDepartureTimePicker = viewModel::onDismissDepartureTimePicker,
                        onDepartureTimeSelected = viewModel::onDepartureTimeSelected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

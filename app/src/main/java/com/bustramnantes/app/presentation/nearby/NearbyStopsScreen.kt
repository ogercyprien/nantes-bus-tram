package com.bustramnantes.app.presentation.nearby

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.OfflineAwareContent
import com.bustramnantes.app.presentation.search.components.StopResultRow

/**
 * "À proximité" tab: the 10 stops closest to the user, rendered with the same [StopResultRow] rows as
 * the Search screen; tapping one opens the identical Stop Detail sheet via [onNavigateToStopDetail]
 * (which navigates to `Screen.StopDetail` exactly like a search result does).
 *
 * On the first resume the list loads from the last-known fix (instant, may be slightly stale). Once a
 * list is shown, later resumes leave it untouched - notably, dismissing the Stop Detail dialog opened
 * from this tab resumes this entry, and reloading from the (cached, possibly old) last-known fix there
 * would revert a list the user had just refreshed. The user forces a **fresh** current-location fix
 * via pull-to-refresh or the app-bar refresh icon - the escape hatch for "I moved and it still shows
 * my old surroundings".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyStopsScreen(
    onNavigateToStopDetail: (stopId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NearbyStopsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
    }

    // Recompute against the user's (last-known) position each time the tab is shown; if permission is
    // still missing after that, prompt for it once (the empty-state CTA re-prompts on demand).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        if (!viewModel.uiState.value.hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("À proximité") },
                actions = {
                    if (uiState.hasLocationPermission) {
                        IconButton(onClick = { viewModel.refreshFresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Actualiser ma position")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        OfflineAwareContent(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when {
                // Only when permission is genuinely missing - never during the initial resolve/load,
                // so a user who has already granted it never sees the "autoriser" prompt flash.
                !uiState.hasLocationPermission -> EmptyState(
                    icon = Icons.Filled.LocationOff,
                    message = "Autorisez la localisation pour voir les arrêts les plus proches de vous.",
                    ctaLabel = "Autoriser",
                    onCta = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                )

                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                uiState.locationUnavailable -> EmptyState(
                    icon = Icons.Filled.LocationOff,
                    message = "Position introuvable pour le moment.",
                    ctaLabel = "Réessayer",
                    onCta = { viewModel.refreshFresh() },
                )

                uiState.stops.isEmpty() -> EmptyState(
                    icon = Icons.Filled.LocationOn,
                    message = "Aucun arrêt à proximité.",
                )

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refreshFresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.stops, key = { it.stop.id }) { result ->
                            StopResultRow(
                                result = result,
                                onClick = { onNavigateToStopDetail(result.stop.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

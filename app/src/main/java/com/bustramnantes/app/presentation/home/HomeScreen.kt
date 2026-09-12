package com.bustramnantes.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.graphics.Color
import com.bustramnantes.app.presentation.common.components.DisruptionAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.OfflineAwareContent

/**
 * Home dashboard: next departures for every saved favorite, ordered by
 * [com.bustramnantes.app.domain.model.Favorite.position].
 *
 * This composable owns the screen's own [TopAppBar] (title + the settings shortcut, accessible
 * from the top-right icon on the Home screen) - the shared
 * [com.bustramnantes.app.presentation.common.components.BottomNavigationBar] is expected to be
 * hosted by the caller wrapping the nav graph, not by this screen.
 *
 * Navigation is fully delegated to the three callbacks below rather than this module depending on
 * `presentation.common.navigation.Screen` directly, keeping Home decoupled from the shared nav
 * graph that a separate integration pass wires up.
 *
 * @param onNavigateToSearch empty-state CTA - navigates to the Recherche tab.
 * @param onNavigateToStopDetail opens the Stop Detail bottom sheet for a
 * favorite's stop, optionally pre-focusing a line/direction.
 * @param onNavigateToSettings top-right icon.
 * @param onNavigateToDisruptions the "Perturbations" icon next to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToStopDetail: (stopId: String, lineId: String?, direction: String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDisruptions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val disruptionsState by viewModel.disruptionsState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Drives the ViewModel's 30s auto-refresh loop only while the screen is actually resumed
    // (auto-refresh every 30 seconds while the screen is foregrounded).
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onEvent(HomeEvent.ScreenResumed)
                Lifecycle.Event.ON_PAUSE -> viewModel.onEvent(HomeEvent.ScreenPaused)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HomeEffect.NavigateToSearch -> onNavigateToSearch()
                is HomeEffect.NavigateToStopDetail ->
                    onNavigateToStopDetail(effect.stopId, effect.lineId, effect.direction)
                is HomeEffect.ShowUndoSnackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Favori supprimé : ${effect.stopName}",
                        actionLabel = "Annuler",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.onEvent(HomeEvent.UndoDeleteClicked)
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Accueil") },
                actions = {
                    // Network-wide disruption notices, with the count of those in force / imminent.
                    IconButton(onClick = onNavigateToDisruptions) {
                        BadgedBox(
                            badge = {
                                if (disruptionsState.currentCount > 0) {
                                    Badge(containerColor = DisruptionAmber, contentColor = Color.Black) {
                                        Text(disruptionsState.currentCount.toString())
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = "Perturbations (${disruptionsState.currentCount})",
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Paramètres")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        OfflineAwareContent(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            when (val state = uiState) {
                HomeUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())

                HomeUiState.Empty -> EmptyState(
                    icon = Icons.Filled.Favorite,
                    message = "Ajoutez des arrêts favoris via la recherche",
                    ctaLabel = "Rechercher un arrêt",
                    onCta = { viewModel.onEvent(HomeEvent.EmptyStateCtaClicked) },
                )

                is HomeUiState.Content -> HomeContent(
                    state = state,
                    onEvent = viewModel::onEvent,
                )
            }
        }
    }
}

/**
 * Reorder mode uses up/down [androidx.compose.material3.IconButton] controls per card rather than
 * a full pointer-drag gesture (`detectDragGestures` + item-offset math). Given this environment
 * cannot build/run Compose to verify gesture/index math, the simpler, fully deterministic and
 * equally accessible (48dp targets, TalkBack labels) up/down affordance was chosen
 * over a bespoke drag implementation; a decorative
 * [androidx.compose.material.icons.filled.DragHandle] icon still communicates reorder affordance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState.Content,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { onEvent(HomeEvent.RefreshRequested) },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isReorderMode) {
                item(key = "reorder_header") {
                    ReorderModeHeader(
                        onDone = { onEvent(HomeEvent.ReorderModeExited) },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            items(state.cards, key = { it.favorite.id }) { card ->
                FavoriteCard(
                    card = card,
                    isReorderMode = state.isReorderMode,
                    isMenuOpen = state.menuOpenFavoriteId == card.favorite.id,
                    canMoveUp = state.cards.first().favorite.id != card.favorite.id,
                    canMoveDown = state.cards.last().favorite.id != card.favorite.id,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReorderModeHeader(onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Réorganiser les favoris", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onDone) { Text("Terminé") }
    }
}

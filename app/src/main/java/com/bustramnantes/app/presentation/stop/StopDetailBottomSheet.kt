package com.bustramnantes.app.presentation.stop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.presentation.common.components.DepartureTimeText
import com.bustramnantes.app.presentation.common.components.DisruptionBox
import com.bustramnantes.app.presentation.common.components.DisruptionDialog
import com.bustramnantes.app.presentation.common.components.LineBadgeWithAlert
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize
import com.bustramnantes.app.presentation.common.utils.END_OF_SERVICE_LABEL
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Minimum sheet-content height as a fraction of the available height. Kept high (and paired with
 * `skipPartiallyExpanded = true` below) so the sheet opens tall enough to show the next departures
 * immediately - the previous ~60% partial peek opened too low and had to be dragged up, made worse
 * by the (now-removed) map thumbnail eating vertical space.
 */
private const val STOP_DETAIL_MIN_HEIGHT_FRACTION = 0.9f

/**
 * Stop Detail bottom sheet: stop header, an actions row (favorite granularity
 * picker, plan from/to here) and departures grouped by line/direction with 30s auto-refresh. There
 * is intentionally no map thumbnail - it added no value and pushed the departures below the fold.
 *
 * This composable owns the [ModalBottomSheet] itself and is called from the
 * `dialog(Screen.StopDetail.ROUTE_PATTERN, …)` destination in `AppNavGraph`:
 *
 * ```
 * dialog(route = Screen.StopDetail.ROUTE_PATTERN, arguments = Screen.StopDetail.arguments, …) {
 *     StopDetailBottomSheet(
 *         onNavigateToSearch = { route -> navController.navigate(route) },
 *         onDismiss = { navController.popBackStack() },
 *     )
 * }
 * ```
 *
 * The `stopId`/`lineId`/`direction` route args are read by [StopDetailViewModel] itself via
 * `SavedStateHandle` (Hilt's `hiltViewModel()` scopes it to this back-stack entry) - no args need
 * to be passed into this composable directly.
 *
 * The sheet opens straight to its expanded height (`skipPartiallyExpanded = true`) with a content
 * min-height of [STOP_DETAIL_MIN_HEIGHT_FRACTION] of the available space (via [BoxWithConstraints]),
 * so the next departures are visible immediately without dragging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailBottomSheet(
    onNavigateToSearch: (String) -> Unit,
    onShowOnMap: (stopId: String, stopName: String) -> Unit,
    onOpenTimetable: (StopDetailEffect.OpenTimetable) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    startLowered: Boolean = false,
    viewModel: StopDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Normally open straight to the expanded height so departures are visible at once. When opened
    // via "Voir sur la carte" ([startLowered]), allow a partial detent so the sheet settles at a low
    // peek over the map (draggable up); a transparent scrim then keeps the map behind it visible.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !startLowered)
    // Snackbar host lives *inside* the sheet: the sheet is a Dialog overlay, so a snackbar shown by
    // the underlying screen's scaffold would be drawn behind it and never seen.
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StopDetailEffect.NavigateToSearch -> onNavigateToSearch(effect.route)
                is StopDetailEffect.ShowOnMap -> onShowOnMap(effect.stopId, effect.stopName)
                is StopDetailEffect.OpenTimetable -> onOpenTimetable(effect)
                is StopDetailEffect.ShowMessage -> {
                    val result = snackbarHostState.showSnackbar(effect.message, actionLabel = effect.actionLabel)
                    if (result == SnackbarResult.ActionPerformed) effect.action?.let(viewModel::onEvent)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = if (startLowered) Color.Transparent else BottomSheetDefaults.ScrimColor,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minHeight = maxHeight * STOP_DETAIL_MIN_HEIGHT_FRACTION
                StopDetailSheetInner(
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    showMapAction = true,
                    modifier = Modifier.heightIn(min = minHeight),
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The Stop Detail sheet *body* (header + actions + departures + the favorite-granularity dialog),
 * with no sheet container of its own - so it can be hosted either inside the [ModalBottomSheet]
 * dialog overlay (from Home/Search/Itinerary) or inside the Map screen's non-modal
 * `BottomSheetScaffold` (which keeps the map interactive behind it).
 *
 * [showMapAction] adds the "Voir sur la carte" action; the Map host passes `false` (you are already
 * there).
 */
@Composable
fun StopDetailSheetInner(
    uiState: StopDetailUiState,
    onEvent: (StopDetailEvent) -> Unit,
    showMapAction: Boolean,
    modifier: Modifier = Modifier,
) {
    StopDetailSheetContent(uiState = uiState, onEvent = onEvent, showMapAction = showMapAction, modifier = modifier)

    uiState.openDisruption?.let { disruption ->
        DisruptionDialog(
            disruption = disruption,
            lines = disruption.lineIds.mapNotNull(uiState.linesById::get),
            onDismiss = { onEvent(StopDetailEvent.DismissDisruption) },
        )
    }

    FavoriteDialogs(uiState = uiState, onEvent = onEvent)
}

/** Whichever favorite dialog [StopDetailUiState.favoriteDialog] asks for (see [FavoriteDialog]). */
@Composable
private fun FavoriteDialogs(uiState: StopDetailUiState, onEvent: (StopDetailEvent) -> Unit) {
    val dismiss = { onEvent(StopDetailEvent.DismissFavoriteDialog) }
    when (val dialog = uiState.favoriteDialog) {
        null -> Unit
        FavoriteDialog.Manage -> StopFavoritesDialog(
            stopName = uiState.displayName,
            favorites = uiState.favoritesForStop,
            departureGroups = uiState.departureGroups,
            onEdit = { onEvent(StopDetailEvent.EditFavoriteClicked(it.id)) },
            onRemove = { onEvent(StopDetailEvent.RemoveFavoriteClicked(it)) },
            onAdd = { onEvent(StopDetailEvent.AddFavoriteClicked) },
            onDismiss = dismiss,
        )
        is FavoriteDialog.Add, is FavoriteDialog.Edit -> {
            val mode = when (dialog) {
                is FavoriteDialog.Edit ->
                    uiState.favoritesForStop.find { it.id == dialog.favoriteId }?.let(FavoritePickerMode::Edit)
                else -> FavoritePickerMode.Add
            } ?: return // The edited favorite isn't loaded (yet) - nothing to show.
            // Keyed so moving between pickers starts each one afresh (its selection is `remember`ed).
            key(dialog) {
                FavoriteGranularityDialog(
                    mode = mode,
                    stopName = uiState.displayName,
                    departureGroups = uiState.departureGroups,
                    initialLineId = uiState.focusedLineId,
                    initialDirection = uiState.focusedDirection,
                    existingFavorites = uiState.favoritesForStop,
                    isSaving = uiState.isSavingFavorite,
                    onConfirm = { onEvent(it) },
                    onRemove = { onEvent(StopDetailEvent.RemoveFavoriteClicked(it)) },
                    onDismiss = dismiss,
                )
            }
        }
    }
}

@Composable
private fun StopDetailSheetContent(
    uiState: StopDetailUiState,
    onEvent: (StopDetailEvent) -> Unit,
    showMapAction: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        item { StopDetailHeader(uiState) }

        // Actions come right after the header (no map thumbnail): favourite + plan from/to here.
        item {
            Spacer(Modifier.height(16.dp))
            StopDetailActionsRow(uiState = uiState, showMapAction = showMapAction, onEvent = onEvent)
            HorizontalDivider(Modifier.padding(top = 16.dp))
        }

        // Disruption notices: those about this very stop first (moved/closed stop, diversion), then
        // those about one of its lines as a whole.
        if (!uiState.disruptions.isEmpty) {
            item { DisruptionsSection(uiState, onEvent) }
        }

        if (uiState.showDeparturesSection) {
            departuresSection(uiState, onEvent)
        } else {
            item {
                Text(
                    text = "Aucun horaire pour cette position",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopDetailHeader(uiState: StopDetailUiState) {
    Column {
        Text(
            text = uiState.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )
        // Lines serving this stop, shown as colored badges (offline, from the local network).
        if (uiState.stopLines.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                uiState.stopLines.forEach { line ->
                    LineBadgeWithAlert(
                        line = line,
                        hasDisruption = line.id in uiState.disruptedLineIds,
                        size = LineBadgeSize.Small,
                    )
                }
            }
        }
        if (uiState.stopNotFound) {
            Text(
                text = "Arrêt introuvable",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DisruptionsSection(uiState: StopDetailUiState, onEvent: (StopDetailEvent) -> Unit) {
    val linesOf: (Disruption) -> List<Line> = { d -> d.lineIds.mapNotNull(uiState.linesById::get) }
    val open: (Disruption) -> Unit = { onEvent(StopDetailEvent.DisruptionClicked(it)) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DisruptionBox(
            disruptions = uiState.disruptions.atStop,
            linesOf = linesOf,
            onOpen = open,
            header = "À cet arrêt",
        )
        DisruptionBox(
            disruptions = uiState.disruptions.onLines,
            linesOf = linesOf,
            onOpen = open,
            header = "Sur les lignes de cet arrêt",
        )
    }
}

@Composable
private fun StopDetailActionsRow(
    uiState: StopDetailUiState,
    showMapAction: Boolean,
    onEvent: (StopDetailEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StopDetailActionButton(
            icon = if (uiState.hasAnyFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            label = if (uiState.hasAnyFavorite) "Favoris (${uiState.favoritesForStop.size})" else "Mettre en favori",
            description = if (uiState.hasAnyFavorite) {
                "Gérer les favoris à cet arrêt ou en ajouter un"
            } else {
                "Mettre en favori"
            },
            onClick = { onEvent(StopDetailEvent.FavoriteActionClicked) },
        )
        StopDetailActionButton(
            icon = Icons.Filled.NorthEast,
            label = "Départ d'ici",
            description = "Planifier un itinéraire au départ de cet arrêt",
            onClick = { onEvent(StopDetailEvent.PlanFromHereClicked) },
        )
        StopDetailActionButton(
            icon = Icons.Filled.SouthWest,
            label = "Arrivée ici",
            description = "Planifier un itinéraire à destination de cet arrêt",
            onClick = { onEvent(StopDetailEvent.PlanToHereClicked) },
        )
        // Only outside the Map itself, and only for a real stop (an address has no map marker).
        if (showMapAction && uiState.stop != null) {
            StopDetailActionButton(
                icon = Icons.Filled.Map,
                label = "Voir sur la carte",
                description = "Voir cet arrêt sur la carte",
                onClick = { onEvent(StopDetailEvent.ShowOnMapClicked) },
            )
        }
    }
}

@Composable
private fun StopDetailActionButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = description },
        ) {
            Icon(imageVector = icon, contentDescription = null)
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun LazyListScope.departuresSection(
    uiState: StopDetailUiState,
    onEvent: (StopDetailEvent) -> Unit,
) {
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Prochains départs", style = MaterialTheme.typography.titleMedium)
            uiState.realtimeStatus?.let { RealtimeStatusLabel(it) }
        }
    }

    // A connectivity/other error shows a banner + retry ABOVE the blocks; the line/direction blocks
    // stay visible below (they come from the local cache) so the user still sees what serves the stop.
    if (uiState.departuresErrorMessage != null) {
        item { DeparturesErrorBanner(message = uiState.departuresErrorMessage, onEvent = onEvent) }
    }

    when {
        // Blocks are known (from cache/online) - render them, each showing a spinner while the times
        // load, then filling all at once.
        uiState.departureGroups.isNotEmpty() -> items(uiState.departureGroups, key = { it.groupKey }) { group ->
            // The theoretical-timetable button needs a loaded stop and a numeric direction code
            // (→ 1-based directionId). Hide it otherwise.
            val canOpenTimetable = uiState.stop != null && group.direction.code.toIntOrNull() != null
            DepartureGroupRow(
                group = group,
                isFocused = group.lineId == uiState.focusedLineId &&
                    (uiState.focusedDirection == null || group.direction.code == uiState.focusedDirection),
                isLoadingTimes = uiState.isLoadingDepartures,
                hasError = uiState.departuresErrorMessage != null,
                hasDisruption = group.lineId in uiState.disruptedLineIds,
                onTimetableClick = if (canOpenTimetable) {
                    { onEvent(StopDetailEvent.TimetableClicked(group)) }
                } else {
                    null
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // No blocks known yet and still loading (e.g. cold cache) - a single spinner.
        uiState.isLoadingDepartures -> item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        // No blocks, done loading, no error -> genuinely nothing serves this stop right now.
        uiState.departuresErrorMessage == null -> item {
            Text(
                text = END_OF_SERVICE_LABEL,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/**
 * Where the board's times come from, in a few words, with a small spinner while real-time data is
 * on its way (the shared SIRI slot can be up to 30 s away; a network prefetch usually makes it
 * instant).
 */
@Composable
private fun RealtimeStatusLabel(status: RealtimeStatus) {
    val (text, spinning) = when (status) {
        is RealtimeStatus.Live -> "Temps réel · mis à jour à ${formatClockTime(status.fetchedAtMs)}" to false
        is RealtimeStatus.Cached ->
            "Temps réel de ${formatClockTime(status.fetchedAtMs)}" + (if (status.refreshing) " · actualisation…" else "") to status.refreshing
        RealtimeStatus.Fetching -> "Récupération du temps réel…" to true
        RealtimeStatus.Unavailable -> "Temps réel indisponible · horaires théoriques" to false
        RealtimeStatus.TimetableOnly -> "Horaires théoriques" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 6.dp).size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Formats an epoch-millis wall-clock instant as "HH:mm" in the device timezone (this is the local
 * time the data was fetched, so device tz is correct - unlike departure clock times). */
private fun formatClockTime(epochMs: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

@Composable
private fun DeparturesErrorBanner(message: String, onEvent: (StopDetailEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = { onEvent(StopDetailEvent.RetryDeparturesClicked) },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Réessayer", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DepartureGroupRow(
    group: DepartureGroup,
    isFocused: Boolean,
    isLoadingTimes: Boolean,
    hasError: Boolean,
    onTimetableClick: (() -> Unit)?,
    hasDisruption: Boolean = false,
) {
    val background = if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadgeWithAlert(
                shortName = group.lineNumber,
                backgroundColorHex = group.lineColor,
                textColorHex = group.lineTextColor,
                mode = group.transportMode,
                hasDisruption = hasDisruption,
                size = LineBadgeSize.Medium,
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                // The direction leads: both blocks of a line share its name, so the name is the
                // subtitle and "Direction …" is what tells the blocks apart.
                Text(text = "Direction ${group.direction.name}", style = MaterialTheme.typography.bodyLarge)
                if (group.lineName.isNotBlank()) {
                    Text(
                        text = group.lineName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onTimetableClick != null) {
                IconButton(
                    onClick = onTimetableClick,
                    modifier = Modifier.semantics { contentDescription = "Horaires théoriques" },
                ) {
                    Icon(imageVector = Icons.Outlined.Schedule, contentDescription = null)
                }
            }
        }
        Column(modifier = Modifier.padding(start = 38.dp, top = 6.dp)) {
            when {
                // Show any known times first, so a manual retry / background refresh never hides
                // already-visible departures behind a spinner.
                group.departures.isNotEmpty() -> group.departures.forEach { departure ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DepartureTimeText(departure = departure, modifier = Modifier.padding(vertical = 2.dp))
                        // Short run (ends before the block's terminus): say where it stops.
                        departure.shortRunDestination(group.direction.name)?.let { destination ->
                            Text(
                                text = "→ $destination",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                // Line/direction known from cache, no times yet -> per-block spinner.
                isLoadingTimes -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )

                // Times couldn't be fetched (e.g. offline) and we have none cached for this block.
                hasError -> Text(
                    text = "Horaires indisponibles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )

                // Loaded successfully with no upcoming departure -> end of service.
                else -> Text(
                    text = END_OF_SERVICE_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

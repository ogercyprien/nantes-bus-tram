package com.bustramnantes.app.presentation.itinerary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.ui.graphics.Color
import com.bustramnantes.app.presentation.itinerary.map.ItineraryOptionsMap
import com.bustramnantes.app.presentation.itinerary.map.optionRoutes
import com.bustramnantes.app.presentation.itinerary.map.routeOfJourney
import com.bustramnantes.app.presentation.itinerary.map.journeyMapModel
import com.bustramnantes.app.presentation.itinerary.map.JourneyMap
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.presentation.common.components.DisruptionDialog
import com.bustramnantes.app.presentation.common.navigation.ItineraryTimeMode
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.OfflineAwareContent

/**
 * Itinerary results screen: pushed from Search's "Chercher" button in Itinéraire
 * mode. Shows up to 12 journey options on a map and in a list ([ResultsWithMap]); tapping one swaps
 * in its step-by-step detail view, also over a map, in place (there is no separate nav-graph route
 * for it - see [ItineraryResultsUiState]).
 *
 * [onBackClick] is only invoked when the results list itself is showing - while the detail view is
 * open, back (gesture/button/hardware) instead returns to the list. [onStopClick] is invoked with
 * a stop id when the user taps a concerned stop in the detail view; callers are expected
 * to navigate to `Screen.StopDetail.createRoute(stopId)`.
 *
 * The "Arriver plus tôt / plus tard" bar above the list re-runs the same search 15 min
 * earlier/later in place (no new back-stack entry); each shift is reported through
 * [onSearchTimeShifted] (nav-contract encoding: mode + ISO `LocalDateTime`) so the host can update
 * the Search form underneath, which then shows the shifted time when the user goes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryResultsScreen(
    onBackClick: () -> Unit,
    onStopClick: (stopId: String) -> Unit,
    onOpenTimetable: (route: String) -> Unit,
    modifier: Modifier = Modifier,
    onSearchTimeShifted: (timeMode: ItineraryTimeMode, departureAt: String) -> Unit = { _, _ -> },
    viewModel: ItineraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeShiftState by viewModel.timeShiftState.collectAsStateWithLifecycle()
    val disruptionsState by viewModel.disruptionsState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ItineraryResultsEffect.SearchTimeShifted -> onSearchTimeShifted(effect.timeMode, effect.departureAt)
            }
        }
    }
    val selectedIndex = (uiState as? ItineraryResultsUiState.Success)?.selectedJourneyIndex
    val isDetailShowing = selectedIndex != null

    BackHandler(enabled = isDetailShowing) {
        viewModel.onEvent(ItineraryResultsEvent.CloseJourneyDetail)
    }

    // Refresh on every foreground return EXCEPT the first (which coincides with the ViewModel's
    // init search), so backgrounding and coming back re-fetches fresh "Partir maintenant" times.
    var isInitialResume by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (isInitialResume) isInitialResume = false else viewModel.onScreenResumed()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // The time-shift bar rides in the top-bar slot, i.e. pinned above every list-level state
            // (loading/empty/error/results): it doesn't flicker away while a shifted search loads
            // and stays reachable when a shift found nothing. Hidden in the journey detail view.
            Column {
                ItineraryResultsTopBar(
                    fromLabel = viewModel.fromLabel,
                    toLabel = viewModel.toLabel,
                    isDetail = isDetailShowing,
                    onBackClick = {
                        if (isDetailShowing) {
                            viewModel.onEvent(ItineraryResultsEvent.CloseJourneyDetail)
                        } else {
                            onBackClick()
                        }
                    },
                )
                if (!isDetailShowing) {
                    ItineraryTimeShiftBar(
                        state = timeShiftState,
                        onShiftEarlier = { viewModel.onEvent(ItineraryResultsEvent.ShiftEarlier) },
                        onShiftLater = { viewModel.onEvent(ItineraryResultsEvent.ShiftLater) },
                    )
                }
                // Timetable results are on screen; the real-time check updates them when it lands.
                if ((uiState as? ItineraryResultsUiState.Success)?.realtimeChecking == true) {
                    RealtimeCheckBanner()
                }
            }
        },
    ) { paddingValues ->
        OfflineAwareContent(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            when (val state = uiState) {
                ItineraryResultsUiState.Loading -> LoadingContent()

                is ItineraryResultsUiState.Error -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    message = state.message,
                    ctaLabel = "Réessayer",
                    onCta = { viewModel.onEvent(ItineraryResultsEvent.Retry) },
                )

                ItineraryResultsUiState.Empty -> EmptyState(
                    icon = Icons.Filled.SearchOff,
                    message = "Aucun itinéraire trouvé pour ce trajet.",
                )

                is ItineraryResultsUiState.Success -> {
                    val selectedJourney = state.selectedJourneyIndex?.let { state.journeys.getOrNull(it) }
                    if (selectedJourney != null) {
                        JourneyDetailWithMap(journey = selectedJourney, onStopClick = onStopClick) { sheetModifier ->
                            JourneyDetailContent(
                                journey = selectedJourney,
                                onStopClick = onStopClick,
                                timetableRoutes = state.timetableRoutes,
                                onOpenTimetable = onOpenTimetable,
                                fromLabel = viewModel.fromLabel,
                                toLabel = viewModel.toLabel,
                                legDisruptions = disruptionsState.byLeg,
                                linesOf = { d -> d.lineIds.mapNotNull(disruptionsState.linesById::get) },
                                onOpenDisruption = { viewModel.onEvent(ItineraryResultsEvent.DisruptionClicked(it)) },
                                modifier = sheetModifier,
                            )
                        }
                    } else {
                        ResultsWithMap(
                            state = state,
                            legDisruptions = disruptionsState.byLeg,
                            onJourneyClick = { index -> viewModel.onEvent(ItineraryResultsEvent.SelectJourney(index)) },
                            onHighlight = { index -> viewModel.onEvent(ItineraryResultsEvent.HighlightJourney(index)) },
                        )
                    }
                }
            }
        }
    }

    disruptionsState.openDisruption?.let { d ->
        DisruptionDialog(
            disruption = d,
            lines = d.lineIds.mapNotNull(disruptionsState.linesById::get),
            onDismiss = { viewModel.onEvent(ItineraryResultsEvent.DismissDisruption) },
        )
    }
}

/**
 * The journey detail view: the journey on a map ([JourneyMap], full size) under a non-modal bottom
 * sheet holding the step-by-step [detail] - opened half-way, drag it up to read every step or down
 * to see the map (the map stays pannable under it, as on the Plan tab). Without any drawable geometry
 * the detail is shown alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JourneyDetailWithMap(
    journey: Journey,
    onStopClick: (stopId: String) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    val model = remember(journey) { journeyMapModel(journey) }
    if (model == null) {
        detail(Modifier.fillMaxSize())
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val peek = maxHeight * DETAIL_SHEET_PEEK_FRACTION
        BottomSheetScaffold(
            scaffoldState = rememberBottomSheetScaffoldState(),
            sheetPeekHeight = peek,
            sheetContent = {
                // Fill the sheet so the detail's list drives it: collapsing once scrolled to the top.
                detail(Modifier.fillMaxWidth().fillMaxHeight())
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            JourneyMap(
                model = model,
                bottomInset = peek,
                onStopClick = onStopClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Share of the screen the detail sheet covers when it opens (the map shows above it). */
private const val DETAIL_SHEET_PEEK_FRACTION = 0.5f

/** "Vérification des données temps réel…" with a small spinner, above the results while the check runs. */
@Composable
private fun RealtimeCheckBanner(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "Vérification des données temps réel…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/**
 * The results on a map: every route of the list in its own colour, side by side where they share a
 * path ([ItineraryOptionsMap]), under a non-modal sheet holding the list. The sheet opens over
 * [RESULTS_SHEET_FRACTION] of the screen and collapses to a strip ([ResultsSheetHeader]) that shows the
 * journey highlighted on the map - tap a route's line to highlight it, the strip (or its card) to
 * open it. Without any drawable route, the list alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsWithMap(
    state: ItineraryResultsUiState.Success,
    legDisruptions: Map<JourneySection.Transit, List<Disruption>>,
    onJourneyClick: (Int) -> Unit,
    onHighlight: (Int?) -> Unit,
) {
    val routes = remember(state.journeys) { optionRoutes(state.journeys) }
    val routeOf = remember(routes) { routeOfJourney(routes) }
    val colors = remember(routes) { routeOf.mapValues { (_, r) -> Color(android.graphics.Color.parseColor(routes[r].color)) } }
    val notices = listOfNotNull(state.scheduleNotice, state.phantomNotice)
    val highlighted = state.highlightedJourneyIndex?.takeIf { it in state.journeys.indices }
    val listState = rememberLazyListState()
    LaunchedEffect(highlighted) {
        if (highlighted != null) listState.animateScrollToItem(notices.size + highlighted)
    }
    if (routes.isEmpty()) {
        JourneyResultsList(state.journeys, notices, legDisruptions, colors, highlighted, listState, onJourneyClick, Modifier.fillMaxSize())
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetHeight = maxHeight * RESULTS_SHEET_FRACTION
        BottomSheetScaffold(
            scaffoldState = rememberBottomSheetScaffoldState(
                bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded),
            ),
            sheetPeekHeight = RESULTS_SHEET_STRIP_HEIGHT,
            sheetDragHandle = { SheetHandle() },
            sheetContent = {
                // A fixed height: "expanded" = this much of the screen, the map stays visible above.
                Column(modifier = Modifier.fillMaxWidth().height(sheetHeight - SHEET_HANDLE_HEIGHT)) {
                    ResultsSheetHeader(
                        journey = highlighted?.let(state.journeys::get),
                        accentColor = highlighted?.let(colors::get),
                        journeyCount = state.journeys.size,
                        onClick = { highlighted?.let(onJourneyClick) },
                    )
                    JourneyResultsList(
                        state.journeys, notices, legDisruptions, colors, highlighted, listState, onJourneyClick,
                        Modifier.fillMaxWidth().weight(1f),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            ItineraryOptionsMap(
                routes = routes,
                highlighted = highlighted?.let(routeOf::get),
                bottomInset = sheetHeight,
                onRouteTap = { r ->
                    // Keep the highlighted journey when its route is tapped again; else the route's first.
                    val keep = highlighted != null && r != null && routeOf[highlighted] == r
                    onHighlight(if (keep) highlighted else r?.let { routes[it].journeyIndices.first() })
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Share of the screen the results sheet covers when open. */
private const val RESULTS_SHEET_FRACTION = 0.55f
private val SHEET_HANDLE_HEIGHT = 20.dp
private val SHEET_HEADER_HEIGHT = 76.dp
/** Collapsed sheet: the handle + [ResultsSheetHeader]. */
private val RESULTS_SHEET_STRIP_HEIGHT = SHEET_HANDLE_HEIGHT + SHEET_HEADER_HEIGHT

@Composable
private fun SheetHandle() {
    Box(modifier = Modifier.fillMaxWidth().height(SHEET_HANDLE_HEIGHT), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * The strip the collapsed results sheet shows: the journey highlighted on the map (times, duration,
 * lines; tap = its detail), else how many journeys there are and how to pick one on the map.
 */
@Composable
private fun ResultsSheetHeader(
    journey: Journey?,
    accentColor: Color?,
    journeyCount: Int,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(SHEET_HEADER_HEIGHT)
            .clickable(enabled = journey != null, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (journey == null) {
            Column {
                Text(
                    text = if (journeyCount == 1) "1 itinéraire" else "$journeyCount itinéraires",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Touchez un tracé sur la carte pour le repérer dans la liste",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Row
        }
        Box(
            modifier = Modifier
                .size(width = 6.dp, height = 48.dp)
                .background(accentColor ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = "${formatClockTime(journey.departureTime)} → ${formatClockTime(journey.arrivalTime)} · " +
                    formatJourneyDuration(journey.totalDurationSeconds),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            ModeSequenceRow(sections = journey.sections, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Voir le détail",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JourneyResultsList(
    journeys: List<Journey>,
    notices: List<String>,
    legDisruptions: Map<JourneySection.Transit, List<Disruption>>,
    colors: Map<Int, Color>,
    highlighted: Int?,
    listState: LazyListState,
    onJourneyClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Scrolls with the list rather than pinning: it explains the (single, walking) result below
        // it, so it belongs right above that card.
        notices.forEach { notice ->
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        itemsIndexed(journeys) { index, journey ->
            JourneySummaryCard(
                journey = journey,
                onClick = { onJourneyClick(index) },
                legDisruptions = legDisruptions,
                accentColor = colors[index],
                highlighted = index == highlighted,
            )
        }
    }
}

/**
 * "Arriver plus tôt" / "Arriver plus tard" (±15 min, same time mode - see
 * [com.bustramnantes.app.domain.util.shiftItineraryTime]) under the time currently searched.
 * "Plus tôt" is disabled in "maintenant" mode; both are disabled at the date picker's bounds.
 */
@Composable
private fun ItineraryTimeShiftBar(
    state: ItineraryTimeShiftUiState,
    onShiftEarlier: () -> Unit,
    onShiftLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.timeLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onShiftEarlier,
                enabled = state.canShiftEarlier,
                modifier = Modifier.weight(1f),
            ) { Text("Arriver plus tôt", maxLines = 1) }
            OutlinedButton(
                onClick = onShiftLater,
                enabled = state.canShiftLater,
                modifier = Modifier.weight(1f),
            ) { Text("Arriver plus tard", maxLines = 1) }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItineraryResultsTopBar(
    fromLabel: String,
    toLabel: String,
    isDetail: Boolean,
    onBackClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = if (isDetail) "Détail du trajet" else "Itinéraires",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "$fromLabel → $toLabel",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
        },
    )
}

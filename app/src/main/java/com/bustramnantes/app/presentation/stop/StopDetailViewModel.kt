package com.bustramnantes.app.presentation.stop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.DepartureSource
import com.bustramnantes.app.domain.model.StopBoard
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.ServedLine
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.usecase.AddFavoriteUseCase
import com.bustramnantes.app.domain.usecase.GetCachedStopLinesUseCase
import com.bustramnantes.app.domain.usecase.GetStopTimetableUseCase
import com.bustramnantes.app.domain.usecase.directionIdFromCode
import com.bustramnantes.app.domain.util.StopDisruptions
import com.bustramnantes.app.domain.util.forStop
import com.bustramnantes.app.domain.util.normalizeForMatch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.bustramnantes.app.presentation.common.navigation.Screen
import com.bustramnantes.app.presentation.common.navigation.SearchPrefillMode
import com.bustramnantes.app.presentation.common.navigation.SearchPrefillTarget
import com.bustramnantes.app.presentation.common.utils.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Nantes local time zone - "today" for the theoretical-timetable prefetch is resolved in it. */
private val PARIS: TimeZone = TimeZone.of("Europe/Paris")

/**
 * Drives the Stop Detail bottom sheet. Route args come from
 * `stop_detail/{stopId}?lineId={lineId}&direction={direction}` ([Screen.StopDetail] - the fixed
 * NAV_CONTRACT this project's screens share); `stopId` is required, `lineId`/`direction` are
 * optional and only pre-focus a section of the departures list.
 *
 * **Best-effort address support**: also opens on arbitrary coordinates (e.g. an address picked in
 * Search, which has no `stopId`) showing only the map thumbnail and no timetable section.
 * [Screen.StopDetail]'s `ROUTE_PATTERN` declares `addressLabel`/`addressLat`/`addressLon` as
 * optional query args (mirroring `lineId`/`direction`) alongside a synthetic `"address:<lat>:<lon>"`
 * `stopId` (see `presentation/search/SearchNavigation.kt`'s `addressStopDetailRoute`). This
 * ViewModel reads those three keys from [SavedStateHandle] and falls back to address mode whenever
 * [StopRepository.getStopById] can't resolve `stopId` *and* those coordinates are present.
 */
@HiltViewModel
class StopDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stopRepository: StopRepository,
    private val favoriteRepository: FavoriteRepository,
    private val getStopTimetableUseCase: GetStopTimetableUseCase,
    private val getCachedStopLinesUseCase: GetCachedStopLinesUseCase,
    private val addFavoriteUseCase: AddFavoriteUseCase,
    private val disruptionRepository: DisruptionRepository,
    lineRepository: LineRepository,
) : ViewModel() {

    // The stop currently shown. A `var` (not a nav-arg `val`) so this same ViewModel can be
    // *re-targeted* at a different stop via [selectStop] - which is how the Map screen reuses one
    // instance as the user taps around, instead of a leaking per-stop instance. The dialog entry
    // points still seed it once from the `stopId` route arg below.
    private var currentStopId: String = savedStateHandle.get<String>("stopId").orEmpty()
    private var focusedLineId: String? = savedStateHandle["lineId"]
    private var focusedDirection: String? = savedStateHandle["direction"]

    // Best-effort extras - see class doc.
    private val addressLabelArg: String? = savedStateHandle["addressLabel"]
    private val addressLatArg: Double? = savedStateHandle.get<String>("addressLat")?.toDoubleOrNull()
    private val addressLonArg: Double? = savedStateHandle.get<String>("addressLon")?.toDoubleOrNull()

    private val _uiState = MutableStateFlow(
        StopDetailUiState(
            stopId = currentStopId,
            focusedLineId = focusedLineId,
            focusedDirection = focusedDirection,
        ),
    )
    val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<StopDetailEffect>()
    val effects: SharedFlow<StopDetailEffect> = _effects.asSharedFlow()

    private var timetablePollingJob: Job? = null

    /** Latest full favorites list, kept so [selectStop] can re-filter it for the newly-targeted stop. */
    private var allFavorites: List<Favorite> = emptyList()

    /**
     * All lines/directions boardable at this stop, from the local GTFS network (independent of service
     * hours, terminus-aware). Merged with each live timetable poll so every line/direction stays
     * visible - with a "fin de service" note when it has no more departures today.
     */
    private var servedLines: List<ServedLine> = emptyList()

    /** Most recent live departures, kept so the served-lines refine can rebuild blocks without waiting for the next poll. */
    private var lastDepartures: List<Departure> = emptyList()

    init {
        observeFavoritesForStop()
        observeDisruptions(lineRepository)
        // Dialog entry points arrive with a stopId (or address coords) already set; the Map screen
        // creates this ViewModel empty and drives it later via [selectStop], so stay idle until then.
        if (currentStopId.isNotBlank() || (addressLatArg != null && addressLonArg != null)) {
            loadStopAndMaybeStartPolling()
        } else {
            _uiState.update { it.copy(isLoadingStop = false) }
        }
    }

    /**
     * Re-points this ViewModel at a different stop (used by the Map's in-screen bottom sheet as the
     * user taps around). Cancels the current polling, resets the sheet state, and reloads - so one
     * ViewModel instance serves every stop the user opens on the map (no per-stop instance leak, no
     * accumulating 30s pollers).
     */
    fun selectStop(stopId: String) {
        if (stopId.isBlank() || (stopId == currentStopId && _uiState.value.stop != null)) return
        currentStopId = stopId
        focusedLineId = null
        focusedDirection = null
        timetablePollingJob?.cancel()
        servedLines = emptyList()
        lastDepartures = emptyList()
        _uiState.value = StopDetailUiState(
            stopId = stopId,
            isLoadingStop = true,
            favoritesForStop = allFavorites.filter { it.stopId == stopId },
            linesById = _uiState.value.linesById,
        )
        loadStopAndMaybeStartPolling()
    }

    /** Clears the current selection and stops polling - the Map calls this when its sheet is hidden. */
    fun clearSelection() {
        currentStopId = ""
        timetablePollingJob?.cancel()
        timetablePollingJob = null
        servedLines = emptyList()
        lastDepartures = emptyList()
        _uiState.value = StopDetailUiState(stopId = "", isLoadingStop = false, linesById = _uiState.value.linesById)
    }

    fun onEvent(event: StopDetailEvent) {
        when (event) {
            StopDetailEvent.FavoriteActionClicked -> _uiState.update { it.copy(showFavoriteDialog = true) }
            StopDetailEvent.DismissFavoriteDialog -> _uiState.update { it.copy(showFavoriteDialog = false) }
            is StopDetailEvent.FavoriteGranularityChosen -> onFavoriteGranularityChosen(event)
            StopDetailEvent.PlanFromHereClicked -> emitPlanEffect(SearchPrefillTarget.DEPART)
            StopDetailEvent.PlanToHereClicked -> emitPlanEffect(SearchPrefillTarget.ARRIVE)
            StopDetailEvent.RetryDeparturesClicked -> restartTimetablePolling()
            StopDetailEvent.ShowOnMapClicked -> {
                val stop = uiState.value.stop ?: return
                viewModelScope.launch {
                    _effects.emit(StopDetailEffect.ShowOnMap(stopId = stop.id, stopName = stop.name))
                }
            }
            is StopDetailEvent.TimetableClicked -> openTimetable(event.group)
            is StopDetailEvent.DisruptionClicked -> _uiState.update { it.copy(openDisruption = event.disruption) }
            StopDetailEvent.DismissDisruption -> _uiState.update { it.copy(openDisruption = null) }
        }
    }

    /**
     * Keeps [StopDetailUiState.disruptions] in step with the notices and the shown stop (members +
     * served lines), and asks for a feed refresh whenever a stop is shown (no-op when recent).
     */
    private fun observeDisruptions(lineRepository: LineRepository) {
        lineRepository.observeLines()
            .onEach { lines -> _uiState.update { it.copy(linesById = lines.associateBy { line -> line.id }) } }
            .launchIn(viewModelScope)
        combine(disruptionRepository.disruptions, _uiState.map { it.stop }.distinctUntilChanged()) { all, stop ->
            if (stop == null) return@combine StopDisruptions.NONE to emptySet<String>()
            val now = Clock.System.now()
            val served = servedLines.map { it.lineId }.ifEmpty { stop.linkedLines.map(Line::idFor) }
            val relevant = all.forStop(stop.memberIds, served, now)
            val dotted = relevant.all.filter { it.isActiveAt(now) }
                .flatMap { d -> if (d.lineIds.isEmpty()) served else d.lineIds.filter(served::contains) }
                .toSet()
            relevant to dotted
        }
            .onEach { (relevant, dotted) -> _uiState.update { it.copy(disruptions = relevant, disruptedLineIds = dotted) } }
            .launchIn(viewModelScope)
        _uiState.map { it.stop?.id }.distinctUntilChanged()
            .onEach { id -> if (id != null) viewModelScope.launch { disruptionRepository.refreshIfStale() } }
            .launchIn(viewModelScope)
    }

    /**
     * Emits [StopDetailEffect.OpenTimetable] for a line+direction row (today's date). No-op when no
     * stop is loaded or the direction code isn't numeric - the same conditions under which the button
     * is hidden in the sheet.
     */
    private fun openTimetable(group: DepartureGroup) {
        val stop = uiState.value.stop ?: return
        val directionId = directionIdFromCode(group.direction.code) ?: return
        viewModelScope.launch {
            _effects.emit(
                StopDetailEffect.OpenTimetable(
                    stopId = stop.id,
                    lineNumber = group.lineNumber,
                    directionId = directionId,
                    stopName = uiState.value.displayName,
                ),
            )
        }
    }

    private fun loadStopAndMaybeStartPolling() {
        viewModelScope.launch {
            val resolvedStop = if (currentStopId.isNotBlank()) stopRepository.getStopById(currentStopId) else null
            when {
                resolvedStop != null -> {
                    // Instant + offline: resolve the stop's lines/directions from the local network so
                    // the header badges and the departure blocks render immediately (each block with
                    // a spinner) before any network call - all this info is already known offline.
                    val cached = getCachedStopLinesUseCase(resolvedStop)
                    servedLines = cached.servedLines
                    lastDepartures = emptyList()
                    _uiState.update {
                        it.copy(
                            isLoadingStop = false,
                            stop = resolvedStop,
                            stopNotFound = false,
                            stopLines = cached.lines,
                            departureGroups = buildDepartureGroups(cached.servedLines, emptyList()),
                            isLoadingDepartures = true,
                        )
                    }
                    _uiState.update { it.copy(favoritesForStop = favoritesOf(resolvedStop)) }
                    startTimetablePolling()
                }

                addressLatArg != null && addressLonArg != null -> {
                    // Address-only mode: no timetable section, just header + map thumbnail.
                    _uiState.update {
                        it.copy(
                            isLoadingStop = false,
                            addressMode = true,
                            addressLabel = addressLabelArg,
                            addressLat = addressLatArg,
                            addressLon = addressLonArg,
                            isLoadingDepartures = false,
                        )
                    }
                }

                else -> {
                    _uiState.update {
                        it.copy(isLoadingStop = false, stopNotFound = true, isLoadingDepartures = false)
                    }
                }
            }
        }
    }

    private fun observeFavoritesForStop() {
        favoriteRepository.observeFavorites()
            .onEach { favorites ->
                allFavorites = favorites
                _uiState.update { state ->
                    state.copy(favoritesForStop = state.stop?.let(::favoritesOf) ?: favorites.filter { it.stopId == currentStopId })
                }
            }
            .launchIn(viewModelScope)
    }

    private fun startTimetablePolling() {
        timetablePollingJob?.cancel()
        timetablePollingJob = getStopTimetableUseCase(currentStopId)
            .onEach { board ->
                lastDepartures = board.departures
                _uiState.update {
                    it.copy(
                        isLoadingDepartures = false,
                        departureGroups = groupsFor(servedLines, board.departures),
                        departuresErrorMessage = null,
                        isProvisional = board.provisional,
                        lastUpdatedAtEpochMs = System.currentTimeMillis(),
                        realtimeStatus = board.realtimeStatus(),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun StopBoard.realtimeStatus(): RealtimeStatus = when {
        source == DepartureSource.SIRI && !stale -> RealtimeStatus.Live(realtimeFetchedAtMs ?: System.currentTimeMillis())
        source == DepartureSource.SIRI -> RealtimeStatus.Cached(realtimeFetchedAtMs ?: System.currentTimeMillis(), realtimePending)
        !provisional -> RealtimeStatus.TimetableOnly
        realtimePending -> RealtimeStatus.Fetching
        else -> RealtimeStatus.Unavailable
    }

    private fun restartTimetablePolling() {
        if (uiState.value.addressMode) return
        _uiState.update { it.copy(isLoadingDepartures = true, departuresErrorMessage = null) }
        startTimetablePolling()
    }

    private fun onFavoriteGranularityChosen(choice: StopDetailEvent.FavoriteGranularityChosen) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingFavorite = true) }
            val existing = findMatchingFavorite(choice.lineId, choice.direction)
            if (existing != null) {
                favoriteRepository.removeFavorite(existing.id)
                _effects.emit(StopDetailEffect.ShowMessage("Favori retiré"))
            } else {
                addFavoriteUseCase(
                    type = choice.type,
                    stopId = currentStopId,
                    stopName = uiState.value.displayName,
                    lineId = choice.lineId,
                    lineShortName = choice.lineShortName,
                    lineColor = choice.lineColor,
                    lineTextColor = choice.lineTextColor,
                    direction = choice.direction,
                    directionName = choice.directionName,
                )
                _effects.emit(StopDetailEffect.ShowMessage("Favori ajouté"))
            }
            _uiState.update { it.copy(isSavingFavorite = false, showFavoriteDialog = false) }
        }
    }

    /** Favorites saved on any StopPlace of [stop] (a merged stop has several). */
    private fun favoritesOf(stop: Stop): List<Favorite> = allFavorites.filter { it.stopId in stop.memberIds }

    /**
     * Builds the departure groups, first re-keying the [departures] onto the served set so each time
     * lands under the correct line/direction block instead of spawning a duplicate group. See
     * [alignToServedLines].
     */
    private fun groupsFor(served: List<ServedLine>, departures: List<Departure>) =
        buildDepartureGroups(servedLines = served, departures = alignToServedLines(departures, served))

    /**
     * Re-keys departures onto the served-line set: matched to the served line by number, then to a
     * direction by code (GTFS `direction_id` == board `directionId - 1`) or, failing that, by
     * accent-insensitive name. On a match the departure adopts the served line/direction identity
     * (id, colors, name, mode) so [buildDepartureGroups] merges it in. Unmatched departures pass
     * through unchanged (rendered as their own group as a safety net). A no-op for departures that
     * already carry the GTFS identity.
     */
    private fun alignToServedLines(departures: List<Departure>, served: List<ServedLine>): List<Departure> {
        if (served.isEmpty()) return departures
        val servedByNumber = served.associateBy { it.lineNumber }
        return departures.map { departure ->
            val line = servedByNumber[departure.lineNumber] ?: return@map departure
            val direction = line.directions.firstOrNull { it.code == departure.direction.code }
                ?: line.directions.firstOrNull {
                    normalizeForMatch(it.name) == normalizeForMatch(departure.direction.name)
                }
                ?: return@map departure
            departure.copy(
                lineId = line.lineId,
                lineName = line.lineName,
                lineColor = line.lineColor,
                lineTextColor = line.lineTextColor,
                transportMode = line.transportMode,
                direction = direction,
            )
        }
    }

    private fun findMatchingFavorite(lineId: String?, direction: String?): Favorite? =
        uiState.value.favoritesForStop.find { it.lineId == lineId && it.direction == direction }

    private fun emitPlanEffect(target: SearchPrefillTarget) {
        val state = uiState.value
        val route = Screen.Search.createRoute(
            prefillMode = SearchPrefillMode.ITINERARY,
            prefillStopId = state.stop?.id,
            prefillLabel = state.displayName,
            prefillLat = state.displayLat,
            prefillLon = state.displayLon,
            prefillTarget = target,
        )
        viewModelScope.launch { _effects.emit(StopDetailEffect.NavigateToSearch(route)) }
    }
}

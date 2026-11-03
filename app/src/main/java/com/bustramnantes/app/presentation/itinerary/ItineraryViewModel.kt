package com.bustramnantes.app.presentation.itinerary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.util.forLeg
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.bustramnantes.app.domain.repository.ScheduleCoverageRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.usecase.ItineraryPlan
import com.bustramnantes.app.domain.usecase.PlanItineraryUseCase
import com.bustramnantes.app.domain.usecase.itineraryDirectionId
import com.bustramnantes.app.domain.util.ITINERARY_TIME_SHIFT_MINUTES
import com.bustramnantes.app.domain.util.shiftItineraryTime
import com.bustramnantes.app.presentation.common.navigation.ItineraryTimeMode
import com.bustramnantes.app.presentation.common.navigation.Screen
import com.bustramnantes.app.presentation.common.utils.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

/** Cap of 12 journey options, enforced defensively here too. */
private const val MAX_JOURNEYS = 12

/** Nantes local time zone - the itinerary date + leg highlight times are resolved in it. */
private val PARIS: TimeZone = TimeZone.of("Europe/Paris")

/**
 * Drives [ItineraryResultsScreen]. Reads the `itinerary_results` route args
 * ([com.bustramnantes.app.presentation.common.navigation.Screen.ItineraryResults]) straight from
 * [SavedStateHandle] - `hiltViewModel()` in Navigation-Compose ties this handle to the current
 * back-stack entry automatically, so no explicit `NavBackStackEntry` needs to be threaded in.
 *
 * All coordinate args are declared as [androidx.navigation.NavType.StringType] in the nav contract
 * (so they round-trip exactly, including negative longitudes), hence the `toDoubleOrNull()` parsing
 * here rather than reading typed Nav args directly.
 *
 * Fires the search once on init - itinerary results have no live-refresh requirement (unlike
 * timetables) - and again on demand: retry, a foreground return in "maintenant" mode, or the
 * "Arriver plus tôt / plus tard" buttons, which shift [timePreference] by
 * [ITINERARY_TIME_SHIFT_MINUTES] and re-run the same search *in place* (same back-stack entry).
 *
 * A shift is also written back into this entry's own [SavedStateHandle] under the route-arg keys
 * (`timeMode`/`departureAt`), so a recreated ViewModel (process death) resumes the shifted search
 * rather than the original one, and is announced on [effects] so the nav graph can hand the new
 * time back to the Search form underneath (see [ItineraryResultsEffect.SearchTimeShifted]).
 */
@HiltViewModel
class ItineraryViewModel @Inject constructor(
    private val planItineraryUseCase: PlanItineraryUseCase,
    private val stopRepository: StopRepository,
    private val lineRepository: LineRepository,
    private val scheduleCoverageRepository: ScheduleCoverageRepository,
    private val savedStateHandle: SavedStateHandle,
    private val disruptionRepository: DisruptionRepository,
) : ViewModel() {

    /** Last day of the installed timetable (bounds the time-shift buttons like the date picker). */
    private var feedEnd: LocalDate? = null

    val fromLabel: String = savedStateHandle.get<String>("fromLabel").orEmpty()
    val toLabel: String = savedStateHandle.get<String>("toLabel").orEmpty()

    /** Present only when the corresponding endpoint is a stop rather than a free address. */
    val fromStopId: String? = savedStateHandle.get<String>("fromStopId")
    val toStopId: String? = savedStateHandle.get<String>("toStopId")

    private val fromLat: Double = savedStateHandle.get<String>("fromLat")?.toDoubleOrNull() ?: 0.0
    private val fromLon: Double = savedStateHandle.get<String>("fromLon")?.toDoubleOrNull() ?: 0.0
    private val toLat: Double = savedStateHandle.get<String>("toLat")?.toDoubleOrNull() ?: 0.0
    private val toLon: Double = savedStateHandle.get<String>("toLon")?.toDoubleOrNull() ?: 0.0

    /**
     * When to travel, rebuilt from the route's `timeMode` + `departureAt` args (see
     * [Screen.ItineraryResults]). A scheduled mode whose timestamp is missing or unparseable
     * degrades to [ItineraryTimePreference.Now] rather than failing the search. Mutable only
     * through [shiftTime].
     */
    private var timePreference: ItineraryTimePreference = run {
        val at = savedStateHandle.get<String>("departureAt")
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        when (Screen.ItineraryResults.parseTimeMode(savedStateHandle.get<String>("timeMode"))) {
            ItineraryTimeMode.NOW -> ItineraryTimePreference.Now
            ItineraryTimeMode.DEPART_AT -> at?.let(ItineraryTimePreference::DepartAt) ?: ItineraryTimePreference.Now
            ItineraryTimeMode.ARRIVE_BY -> at?.let(ItineraryTimePreference::ArriveBy) ?: ItineraryTimePreference.Now
        }
    }

    private val _uiState = MutableStateFlow<ItineraryResultsUiState>(ItineraryResultsUiState.Loading)
    val uiState: StateFlow<ItineraryResultsUiState> = _uiState.asStateFlow()

    private val _timeShiftState = MutableStateFlow(timeShiftStateFor(timePreference))

    /** The "Arriver plus tôt / plus tard" bar - independent of [uiState] so it survives reloads. */
    val timeShiftState: StateFlow<ItineraryTimeShiftUiState> = _timeShiftState.asStateFlow()

    private val _effects = Channel<ItineraryResultsEffect>(Channel.BUFFERED)

    /** One-shot effects for the hosting nav graph (collected by [ItineraryResultsScreen]). */
    val effects: Flow<ItineraryResultsEffect> = _effects.receiveAsFlow()

    private var searchJob: Job? = null

    private val openDisruption = MutableStateFlow<Disruption?>(null)

    /** Notices per transit leg of the shown journeys - see [ItineraryDisruptionsUiState]. */
    val disruptionsState: StateFlow<ItineraryDisruptionsUiState> = combine(
        _uiState,
        disruptionRepository.disruptions,
        lineRepository.observeLines(),
        openDisruption,
    ) { state, all, lines, open ->
        val legs = (state as? ItineraryResultsUiState.Success)?.journeys.orEmpty()
            .flatMap { it.sections }.filterIsInstance<JourneySection.Transit>().distinct()
        ItineraryDisruptionsUiState(
            byLeg = legs.associateWith { all.forLeg(it) }.filterValues { it.isNotEmpty() },
            linesById = lines.associateBy { it.id },
            openDisruption = open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItineraryDisruptionsUiState())

    init {
        viewModelScope.launch {
            feedEnd = scheduleCoverageRepository.lastScheduledDate()
            search()
        }
        viewModelScope.launch { disruptionRepository.refreshIfStale() }
    }

    /**
     * Re-runs the search when the screen returns to the foreground so the "Partir maintenant" clock
     * times don't go stale while the app was backgrounded. No-op for a scheduled trip (fixed times)
     * or while a journey detail is open (a refresh would reset the list and kick the user out of it).
     */
    fun onScreenResumed() {
        // The bar's enablement depends on the clock (today's date bounds the schedule window).
        _timeShiftState.value = timeShiftStateFor(timePreference)
        if (timePreference != ItineraryTimePreference.Now) return
        val current = _uiState.value
        val viewingDetail = current is ItineraryResultsUiState.Success && current.selectedJourneyIndex != null
        if (!viewingDetail) search()
    }

    fun onEvent(event: ItineraryResultsEvent) {
        when (event) {
            ItineraryResultsEvent.Retry -> search()
            is ItineraryResultsEvent.SelectJourney -> selectJourney(event.index)
            ItineraryResultsEvent.CloseJourneyDetail -> closeJourneyDetail()
            is ItineraryResultsEvent.HighlightJourney -> highlightJourney(event.index)
            ItineraryResultsEvent.ShiftEarlier -> shiftTime(-ITINERARY_TIME_SHIFT_MINUTES)
            ItineraryResultsEvent.ShiftLater -> shiftTime(ITINERARY_TIME_SHIFT_MINUTES)
            is ItineraryResultsEvent.DisruptionClicked -> openDisruption.value = event.disruption
            ItineraryResultsEvent.DismissDisruption -> openDisruption.value = null
        }
    }

    /**
     * Moves the searched time by [deltaMinutes] keeping its mode ("maintenant" + later becomes
     * "Partir à" now + delta - see [shiftItineraryTime]), persists it, tells the Search form, and
     * re-runs the search. A shift the pure function refuses (earlier than "maintenant", or outside
     * the date picker's window) is a no-op - the matching button is disabled anyway.
     */
    private fun shiftTime(deltaMinutes: Int) {
        val shifted = shiftItineraryTime(timePreference, deltaMinutes, nowInParis(), PARIS, feedEnd) ?: return
        val at = shifted.scheduledDateTime ?: return
        val mode = when (shifted) {
            is ItineraryTimePreference.ArriveBy -> ItineraryTimeMode.ARRIVE_BY
            else -> ItineraryTimeMode.DEPART_AT
        }
        timePreference = shifted
        // Same keys (and encodings) as the route args, so the init-time parse above reads them back.
        savedStateHandle["timeMode"] = mode.name
        savedStateHandle["departureAt"] = at.toString()
        _timeShiftState.value = timeShiftStateFor(shifted)
        _effects.trySend(ItineraryResultsEffect.SearchTimeShifted(mode, at.toString()))
        search()
    }

    private fun timeShiftStateFor(preference: ItineraryTimePreference): ItineraryTimeShiftUiState {
        val now = nowInParis()
        return ItineraryTimeShiftUiState(
            timeLabel = formatTimePreference(preference, today = now.date),
            canShiftEarlier = shiftItineraryTime(preference, -ITINERARY_TIME_SHIFT_MINUTES, now, PARIS, feedEnd) != null,
            canShiftLater = shiftItineraryTime(preference, ITINERARY_TIME_SHIFT_MINUTES, now, PARIS, feedEnd) != null,
        )
    }

    private fun nowInParis(): LocalDateTime = Clock.System.now().toLocalDateTime(PARIS)

    private fun search() {
        _uiState.value = ItineraryResultsUiState.Loading
        // A newer search (e.g. a second quick "plus tard" tap) supersedes the in-flight one, so a
        // slow stale answer can't overwrite the fresher results.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Timetable journeys first (instant, with the "vérification des données temps réel" banner),
            // then the real-time-checked plan replaces them in place.
            planItineraryUseCase.stream(
                fromLabel = fromLabel,
                fromLat = fromLat,
                fromLon = fromLon,
                toLabel = toLabel,
                toLat = toLat,
                toLon = toLon,
                timePreference = timePreference,
                fromStopId = fromStopId,
                toStopId = toStopId,
            ).collect { result ->
                when (result) {
                    is DataResult.Success -> publish(result.data)
                    is DataResult.Error -> _uiState.value = ItineraryResultsUiState.Error(
                        message = result.toUserMessage("Impossible de calculer un itinéraire pour ce trajet."),
                    )
                }
            }
        }
    }

    /**
     * Shows [plan]. An open journey detail (and the journey highlighted on the map) stays across the
     * timetable -> real-time update when that journey is still in the list (matched by the trips it rides, so predicted times don't break the
     * match); if it was dropped (it rode a phantom bus) the list is shown, with the notice saying why.
     */
    private fun CoroutineScope.publish(plan: ItineraryPlan) {
        val journeys = plan.journeys.take(MAX_JOURNEYS)
        if (journeys.isEmpty()) {
            _uiState.value = ItineraryResultsUiState.Empty
            return
        }
        val previous = _uiState.value as? ItineraryResultsUiState.Success
        val selectedKey = previous?.selectedJourneyIndex?.let { previous.journeys.getOrNull(it) }?.rideKey()
        val selected = selectedKey?.let { key -> journeys.indexOfFirst { it.rideKey() == key }.takeIf { it >= 0 } }
        val highlightedKey = previous?.highlightedJourneyIndex?.let { previous.journeys.getOrNull(it) }?.rideKey()
        val highlighted = highlightedKey?.let { key -> journeys.indexOfFirst { it.rideKey() == key }.takeIf { it >= 0 } }
        // Show the results as fast as possible; only THEN resolve the per-leg timetable nav routes.
        _uiState.value = ItineraryResultsUiState.Success(
            journeys = journeys,
            selectedJourneyIndex = selected,
            highlightedJourneyIndex = highlighted,
            scheduleNotice = scheduleNoticeFor(journeys),
            phantomNotice = phantomNoticeFor(plan),
            realtimeChecking = plan.realtimeCheckPending,
        )
        launch { resolveTimetableRoutes(journeys) }
    }

    /** A journey's identity across the timetable and real-time versions: the trips it rides. */
    private fun Journey.rideKey(): List<Any> {
        val rides = sections.filterIsInstance<JourneySection.Transit>()
            .map { it.tripId ?: "${it.lineNumber}@${it.scheduledDepartureTime ?: it.departureTime}" }
        return rides.ifEmpty { listOf("walk") }
    }

    /**
     * A scheduled search that came back with journeys but not a single transit leg means the installed
     * timetable has no service for that date (past the feed's end, or a day with no service) - only
     * a walking route is left. Say so; `null` in every other case,
     * including "maintenant" (where a walk-only answer just means the two points are close).
     */
    private fun scheduleNoticeFor(journeys: List<Journey>): String? {
        if (timePreference == ItineraryTimePreference.Now) return null
        val hasTransit = journeys.any { journey -> journey.sections.any { it is JourneySection.Transit } }
        return if (hasTransit) null else "Aucun horaire de transport disponible pour cette date."
    }

    /**
     * Explains the phantom-bus check's effect on the list (see
     * [com.bustramnantes.app.domain.usecase.PlanItineraryUseCase]), or `null` when it changed nothing.
     */
    private fun phantomNoticeFor(plan: ItineraryPlan): String? = when {
        plan.onlyPhantomJourneys ->
            "Les trajets proposés reposent sur un passage probablement supprimé : aucun véhicule n'est suivi " +
                "pour ce bus, il risque de ne pas passer."
        plan.avoidedPhantomJourneys == 1 ->
            "1 trajet écarté : il reposait sur un passage probablement supprimé."
        plan.avoidedPhantomJourneys > 1 ->
            "${plan.avoidedPhantomJourneys} trajets écartés : ils reposaient sur un passage " +
                "probablement supprimé."
        else -> null
    }

    /**
     * For each transit leg, resolves the boarding stop + the direction id, builds the "Horaires
     * théoriques" route (with the leg's departure as the highlight) and publishes those routes into the
     * [ItineraryResultsUiState.Success] state (so the buttons appear). Runs only after results are shown.
     */
    private suspend fun resolveTimetableRoutes(journeys: List<Journey>) {
        val date = timePreference.scheduledDateTime?.date
            ?: journeys.firstOrNull()?.departureTime?.toLocalDateTime(PARIS)?.date
            ?: return

        val routes = LinkedHashMap<JourneySection.Transit, String>()
        val transitLegs = journeys.flatMap { it.sections }.filterIsInstance<JourneySection.Transit>().distinct()
        for (leg in transitLegs) {
            val boarding = leg.concernedStops.firstOrNull() ?: continue
            val stopId = stopRepository.getStopById(boarding.stopId)?.id ?: continue
            val directionId = itineraryDirectionId(lineRepository.getLineByShortName(leg.lineNumber), leg.directionName)
                ?: continue
            routes[leg] = Screen.Timetable.createRoute(
                stopId = stopId,
                lineNumber = leg.lineNumber,
                directionId = directionId,
                date = date,
                stopName = boarding.name,
                highlight = formatHhmm(leg.departureTime),
            )
        }

        if (routes.isNotEmpty()) {
            _uiState.update { current ->
                // Only onto the journeys these routes were built for (a later update re-resolves its own).
                if (current is ItineraryResultsUiState.Success && current.journeys == journeys) current.copy(timetableRoutes = routes) else current
            }
        }
    }

    private fun formatHhmm(instant: kotlinx.datetime.Instant): String {
        val time = instant.toLocalDateTime(PARIS)
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }

    private fun selectJourney(index: Int) {
        val current = _uiState.value
        if (current is ItineraryResultsUiState.Success && index in current.journeys.indices) {
            // Back on the list, the map highlights the journey just looked at.
            _uiState.value = current.copy(selectedJourneyIndex = index, highlightedJourneyIndex = index)
        }
    }

    private fun highlightJourney(index: Int?) {
        val current = _uiState.value
        if (current is ItineraryResultsUiState.Success && (index == null || index in current.journeys.indices)) {
            _uiState.value = current.copy(highlightedJourneyIndex = index)
        }
    }

    private fun closeJourneyDetail() {
        val current = _uiState.value
        if (current is ItineraryResultsUiState.Success) {
            _uiState.value = current.copy(selectedJourneyIndex = null)
        }
    }
}

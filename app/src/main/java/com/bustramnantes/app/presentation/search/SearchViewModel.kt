package com.bustramnantes.app.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.RecentSearch
import com.bustramnantes.app.domain.model.RecentSearchType
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.model.getOrNull
import com.bustramnantes.app.domain.repository.GeocodingRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import com.bustramnantes.app.domain.usecase.PHANTOM_CHECK_HORIZON
import com.bustramnantes.app.domain.usecase.TIMETABLE_REFRESH_INTERVAL_MS
import com.bustramnantes.app.presentation.common.utils.haversineDistanceMeters
import com.bustramnantes.app.domain.repository.RecentSearchRepository
import com.bustramnantes.app.domain.repository.ScheduleCoverageRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.usecase.search.SearchStopsUseCase
import com.bustramnantes.app.domain.usecase.search.StopSearchResult
import com.bustramnantes.app.presentation.common.navigation.ItineraryTimeMode
import com.bustramnantes.app.presentation.common.navigation.Screen
import com.bustramnantes.app.presentation.common.navigation.SearchPrefillMode
import com.bustramnantes.app.presentation.common.navigation.SearchPrefillTarget
import com.bustramnantes.app.presentation.search.location.UserLocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import javax.inject.Inject

private const val ADDRESS_DEBOUNCE_MS = 300L
private const val STOP_SEARCH_DEBOUNCE_MS = 150L
private const val MAX_ITINERARY_SUGGESTIONS = 6

/**
 * Backs [SearchScreen] end to end: Arrêt search, Adresse search, and the Itinéraire two-field
 * input. Journey search itself and the itinerary results list are owned by another
 * screen - this ViewModel only resolves the two endpoints and hands the navigation route off via
 * [buildJourneySearchRoute].
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val stopRepository: StopRepository,
    private val recentSearchRepository: RecentSearchRepository,
    private val geocodingRepository: GeocodingRepository,
    private val searchStopsUseCase: SearchStopsUseCase,
    private val userLocationProvider: UserLocationProvider,
    private val scheduleCoverageRepository: ScheduleCoverageRepository,
    private val realtimeBoards: RealtimeBoardRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var userLat: Double? = null
    private var userLon: Double? = null

    private var stopSearchJob: Job? = null
    private var locationJob: Job? = null
    private var addressSearchJob: Job? = null
    private val itineraryStopJobs = mutableMapOf<SearchPrefillTarget, Job>()
    private val itineraryAddressJobs = mutableMapOf<SearchPrefillTarget, Job>()

    init {
        viewModelScope.launch {
            val feedEnd = scheduleCoverageRepository.lastScheduledDate()
            _uiState.update { it.copy(scheduleFeedEnd = feedEnd) }
        }
        viewModelScope.launch {
            recentSearchRepository.observeRecentSearches().collect { list ->
                _uiState.update { it.copy(recentSearches = list.take(10)) }
            }
        }
        // Best-effort: only refreshes sorting if permission already happens to be granted at
        // start-up. The Composable additionally requests the permission and calls
        // [onLocationPermissionResult], which re-triggers this with a fresh fix once granted.
        refreshLocationThenResearch()
        applyPrefillArgs(savedStateHandle)
        prefetchDepartureBoards()
        runStopSearch("")
    }

    // ---------------------------------------------------------------------------------------
    // Location
    // ---------------------------------------------------------------------------------------

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) refreshLocationThenResearch()
    }

    private fun refreshLocationThenResearch() {
        val location = userLocationProvider.getLastKnownLocation() ?: return
        userLat = location.first
        userLon = location.second
        runStopSearch(_uiState.value.stopQuery)
    }

    /**
     * Re-resolves the user's **current** location each time the Search tab is (re)opened and re-runs
     * the active stop search with it, so the default (blank-query) list reflects where the user is
     * now - not the stale last-known fix cached when the ViewModel was first created (which otherwise
     * never updated). Falls back to the last-known fix internally; a no-op if location is unavailable.
     */
    fun onScreenResumed() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            val location = userLocationProvider.getCurrentLocation() ?: return@launch
            userLat = location.first
            userLon = location.second
            runStopSearch(_uiState.value.stopQuery)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Top-level mode switch
    // ---------------------------------------------------------------------------------------

    fun onTopModeChange(mode: SearchTopMode) {
        _uiState.update { it.copy(topMode = mode) }
    }

    // ---------------------------------------------------------------------------------------
    // Arrêt mode
    // ---------------------------------------------------------------------------------------

    fun onStopQueryChange(query: String) {
        _uiState.update { it.copy(stopQuery = query) }
        runStopSearch(query)
    }

    private fun runStopSearch(query: String) {
        stopSearchJob?.cancel()
        stopSearchJob = viewModelScope.launch {
            // Debounce keystrokes: the cancel above discards the prior in-flight search, and this
            // delay coalesces a burst of typing into a single search (blank query is effectively
            // immediate). Skipped for blank so the initial population isn't delayed noticeably.
            if (query.isNotEmpty()) delay(STOP_SEARCH_DEBOUNCE_MS)
            val results = searchStopsUseCase(query, userLat, userLon)
            _uiState.update { it.copy(favoriteStopResults = results.favorites, stopResults = results.matches) }
        }
    }

    /** Returns the Stop Detail route to navigate to; also records the recent search. */
    fun onStopResultSelected(result: StopSearchResult): String {
        viewModelScope.launch {
            recentSearchRepository.addRecentSearch(
                RecentSearch(
                    id = 0,
                    query = result.stop.name,
                    type = RecentSearchType.STOP,
                    stopId = result.stop.id,
                    lat = null,
                    lon = null,
                    searchedAt = System.currentTimeMillis(),
                ),
            )
        }
        return Screen.StopDetail.createRoute(stopId = result.stop.id)
    }

    fun onDeleteRecentSearch(id: Long) {
        viewModelScope.launch { recentSearchRepository.deleteRecentSearch(id) }
    }

    /** Returns the route to navigate to for a recent search, or `null` if it cannot be resolved. */
    fun onRecentSearchSelected(recent: RecentSearch): String? = when (recent.type) {
        RecentSearchType.STOP -> recent.stopId?.let { Screen.StopDetail.createRoute(stopId = it) }
        RecentSearchType.ADDRESS -> {
            val lat = recent.lat
            val lon = recent.lon
            if (lat != null && lon != null) addressStopDetailRoute(label = recent.query, lat = lat, lon = lon) else null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Adresse mode
    // ---------------------------------------------------------------------------------------

    /** "Rechercher une adresse" - switches sub-mode, pre-filling the query from Arrêt mode. */
    fun onOpenAddressSearch() {
        val prefill = _uiState.value.stopQuery
        _uiState.update { it.copy(stopAddressSubMode = StopAddressSubMode.ADDRESS, addressQuery = prefill) }
        runAddressSearch(prefill)
    }

    fun onCloseAddressSearch() {
        addressSearchJob?.cancel()
        _uiState.update {
            it.copy(
                stopAddressSubMode = StopAddressSubMode.STOP,
                addressResults = emptyList(),
                isAddressSearching = false,
            )
        }
    }

    fun onAddressQueryChange(query: String) {
        _uiState.update { it.copy(addressQuery = query) }
        runAddressSearch(query)
    }

    private fun runAddressSearch(query: String) {
        addressSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(addressResults = emptyList(), isAddressSearching = false) }
            return
        }
        addressSearchJob = viewModelScope.launch {
            _uiState.update { it.copy(isAddressSearching = true) }
            delay(ADDRESS_DEBOUNCE_MS)
            val results = geocodingRepository.searchAddresses(query, userLat, userLon).getOrNull().orEmpty()
            _uiState.update { it.copy(isAddressSearching = false, addressResults = results) }
        }
    }

    /** Returns the (extended, see [addressStopDetailRoute]) Stop Detail route; records recent search. */
    fun onAddressResultSelected(address: AddressResult): String {
        viewModelScope.launch {
            recentSearchRepository.addRecentSearch(
                RecentSearch(
                    id = 0,
                    query = address.label,
                    type = RecentSearchType.ADDRESS,
                    stopId = null,
                    lat = address.lat,
                    lon = address.lon,
                    searchedAt = System.currentTimeMillis(),
                ),
            )
        }
        return addressStopDetailRoute(label = address.label, lat = address.lat, lon = address.lon)
    }

    // ---------------------------------------------------------------------------------------
    // Itinéraire mode - self-contained: reuses the Arrêt/Adresse search
    // techniques inline (StopRepository + GeocodingRepository) rather than a shared use case,
    // since each field independently resolves to either a stop or a free address.
    // ---------------------------------------------------------------------------------------

    fun onItineraryFieldFocusChanged(target: SearchPrefillTarget, focused: Boolean) {
        _uiState.update { state ->
            val active = when {
                focused -> target
                state.activeItineraryField == target -> null
                else -> state.activeItineraryField
            }
            state.copy(activeItineraryField = active)
        }
    }

    fun onItineraryQueryChange(target: SearchPrefillTarget, query: String) {
        updateField(target) { it.copy(query = query, resolved = null) }

        itineraryStopJobs[target]?.cancel()
        itineraryStopJobs[target] = viewModelScope.launch {
            val stops = if (query.isBlank()) {
                emptyList()
            } else {
                delay(STOP_SEARCH_DEBOUNCE_MS)
                stopRepository.searchStops(query, userLat, userLon).take(MAX_ITINERARY_SUGGESTIONS)
            }
            updateField(target) { it.copy(stopSuggestions = stops) }
        }

        itineraryAddressJobs[target]?.cancel()
        if (query.isBlank()) {
            updateField(target) { it.copy(addressSuggestions = emptyList(), isSearchingAddress = false) }
            return
        }
        itineraryAddressJobs[target] = viewModelScope.launch {
            updateField(target) { it.copy(isSearchingAddress = true) }
            delay(ADDRESS_DEBOUNCE_MS)
            val addresses = geocodingRepository.searchAddresses(query, userLat, userLon)
                .getOrNull()
                .orEmpty()
                .take(MAX_ITINERARY_SUGGESTIONS)
            updateField(target) { it.copy(addressSuggestions = addresses, isSearchingAddress = false) }
        }
    }

    fun onItineraryStopSelected(target: SearchPrefillTarget, stop: Stop) {
        cancelItineraryJobs(target)
        updateField(target) {
            it.copy(
                query = stop.name,
                resolved = ItineraryEndpoint.FromStop(stop),
                stopSuggestions = emptyList(),
                addressSuggestions = emptyList(),
                isSearchingAddress = false,
            )
        }
        _uiState.update { it.copy(activeItineraryField = null) }
    }

    fun onItineraryAddressSelected(target: SearchPrefillTarget, address: AddressResult) {
        cancelItineraryJobs(target)
        updateField(target) {
            it.copy(
                query = address.label,
                resolved = ItineraryEndpoint.FromAddress(address),
                stopSuggestions = emptyList(),
                addressSuggestions = emptyList(),
                isSearchingAddress = false,
            )
        }
        _uiState.update { it.copy(activeItineraryField = null) }
    }

    fun onOpenDepartureTimePicker() {
        _uiState.update { it.copy(isDepartureTimePickerOpen = true) }
    }

    fun onDismissDepartureTimePicker() {
        _uiState.update { it.copy(isDepartureTimePickerOpen = false) }
    }

    fun onDepartureTimeSelected(mode: DepartureTimeMode, dateTime: LocalDateTime?) {
        _uiState.update {
            it.copy(
                departureTimeMode = mode,
                departureDateTime = if (mode == DepartureTimeMode.NOW) null else dateTime,
                isDepartureTimePickerOpen = false,
            )
        }
    }

    /**
     * The itinerary results screen shifted the searched time in place ("Arriver plus tôt / plus
     * tard"); mirror it into the form so going back shows - and re-searches with - the shifted time.
     * Args use the nav contract's encodings (see `Screen.Search.ITINERARY_TIME_MODE_RESULT_KEY`); an
     * unparseable timestamp is ignored rather than wiping the user's choice.
     */
    fun onItineraryTimeShiftedInResults(timeMode: ItineraryTimeMode, departureAt: String) {
        val mode = when (timeMode) {
            ItineraryTimeMode.NOW -> DepartureTimeMode.NOW
            ItineraryTimeMode.DEPART_AT -> DepartureTimeMode.DEPART_AT
            ItineraryTimeMode.ARRIVE_BY -> DepartureTimeMode.ARRIVE_BY
        }
        val dateTime = runCatching { LocalDateTime.parse(departureAt) }.getOrNull()
        if (mode != DepartureTimeMode.NOW && dateTime == null) return
        _uiState.update {
            it.copy(
                departureTimeMode = mode,
                departureDateTime = if (mode == DepartureTimeMode.NOW) null else dateTime,
            )
        }
    }

    fun onSwapItineraryFields() {
        itineraryStopJobs.values.forEach { it.cancel() }
        itineraryAddressJobs.values.forEach { it.cancel() }
        itineraryStopJobs.clear()
        itineraryAddressJobs.clear()
        _uiState.update { state ->
            state.copy(
                departField = state.arriveField.copy(target = SearchPrefillTarget.DEPART, label = "Départ"),
                arriveField = state.departField.copy(target = SearchPrefillTarget.ARRIVE, label = "Arrivée"),
                activeItineraryField = null,
            )
        }
    }

    /** Returns the `itinerary_results` route once both endpoints are resolved, else `null`. */
    fun buildJourneySearchRoute(): String? {
        val state = _uiState.value
        val from = state.departField.resolved ?: return null
        val to = state.arriveField.resolved ?: return null
        return Screen.ItineraryResults.createRoute(
            fromLabel = from.label,
            fromLat = from.lat,
            fromLon = from.lon,
            fromStopId = from.stopId,
            toLabel = to.label,
            toLat = to.lat,
            toLon = to.lon,
            toStopId = to.stopId,
            timeMode = state.departureTimeMode.toNavTimeMode(),
            // Only carried for a scheduled mode; ISO-8601, per the nav contract.
            departureAt = state.departureDateTime
                .takeIf { state.departureTimeMode != DepartureTimeMode.NOW }
                ?.toString(),
        )
    }

    /**
     * Warms the real-time boards the itinerary search will need: as soon as the Départ is set (and the
     * trip leaves now or within [PHANTOM_CHECK_HORIZON]), registers its stop - or, for an address, the
     * stops within [PREFETCH_RADIUS_M] - with the shared SIRI scheduler, so the results screen's
     * phantom check usually finds fresh boards instead of waiting for the next 30 s slot. Re-asked every
     * 30 s (interest lapses after ~75 s) for at most [PREFETCH_DURATION_MS], so a form left open doesn't
     * keep spending the quota. Restarted whenever the Départ or the time changes.
     */
    private fun prefetchDepartureBoards() {
        viewModelScope.launch {
            _uiState
                .map { Triple(it.departField.resolved, it.departureTimeMode, it.departureDateTime) }
                .distinctUntilChanged()
                .collectLatest { (from, mode, at) ->
                    if (from == null || !leavesSoon(mode, at)) return@collectLatest
                    val stopIds = when (from) {
                        is ItineraryEndpoint.FromStop -> listOf(from.stop.id)
                        is ItineraryEndpoint.FromAddress ->
                            stopRepository.getNearbyStops(from.lat, from.lon, PREFETCH_MAX_STOPS)
                                .filter { haversineDistanceMeters(from.lat, from.lon, it.lat, it.lon) <= PREFETCH_RADIUS_M }
                                .map { it.id }
                    }
                    if (stopIds.isEmpty()) return@collectLatest
                    val until = System.currentTimeMillis() + PREFETCH_DURATION_MS
                    while (System.currentTimeMillis() < until) {
                        runCatching { realtimeBoards.boards(stopIds, RealtimePriority.ITINERARY) }
                        delay(TIMETABLE_REFRESH_INTERVAL_MS)
                    }
                }
        }
    }

    private fun leavesSoon(mode: DepartureTimeMode, at: LocalDateTime?): Boolean = when (mode) {
        DepartureTimeMode.NOW -> true
        DepartureTimeMode.DEPART_AT -> at != null &&
            (at.toInstant(PARIS) - Clock.System.now()) in (-5).minutes..PHANTOM_CHECK_HORIZON
        DepartureTimeMode.ARRIVE_BY -> false
    }

    private fun cancelItineraryJobs(target: SearchPrefillTarget) {
        itineraryStopJobs.remove(target)?.cancel()
        itineraryAddressJobs.remove(target)?.cancel()
    }

    private fun updateField(target: SearchPrefillTarget, transform: (ItineraryFieldUiState) -> ItineraryFieldUiState) {
        _uiState.update { state ->
            when (target) {
                SearchPrefillTarget.DEPART -> state.copy(departField = transform(state.departField))
                SearchPrefillTarget.ARRIVE -> state.copy(arriveField = transform(state.arriveField))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Incoming prefill args (NAV_CONTRACT `search?prefillMode=...`, from Stop Detail's
    // "Plan FROM/TO here" actions).
    // ---------------------------------------------------------------------------------------

    private fun applyPrefillArgs(savedStateHandle: SavedStateHandle) {
        val mode = savedStateHandle.get<String>("prefillMode")
            ?.let { runCatching { SearchPrefillMode.valueOf(it) }.getOrNull() }
            ?: return

        val label = savedStateHandle.get<String>("prefillLabel")

        when (mode) {
            SearchPrefillMode.STOP -> {
                _uiState.update {
                    it.copy(topMode = SearchTopMode.STOP, stopAddressSubMode = StopAddressSubMode.STOP)
                }
                label?.let { onStopQueryChange(it) }
            }

            SearchPrefillMode.ADDRESS -> {
                _uiState.update {
                    it.copy(
                        topMode = SearchTopMode.STOP,
                        stopAddressSubMode = StopAddressSubMode.ADDRESS,
                        addressQuery = label.orEmpty(),
                    )
                }
                runAddressSearch(label.orEmpty())
            }

            SearchPrefillMode.ITINERARY -> {
                val lat = savedStateHandle.get<String>("prefillLat")?.toDoubleOrNull()
                val lon = savedStateHandle.get<String>("prefillLon")?.toDoubleOrNull()
                val stopId = savedStateHandle.get<String>("prefillStopId")
                val target = savedStateHandle.get<String>("prefillTarget")
                    ?.let { runCatching { SearchPrefillTarget.valueOf(it) }.getOrNull() }
                    ?: SearchPrefillTarget.DEPART

                _uiState.update { it.copy(topMode = SearchTopMode.ITINERARY) }

                if (label != null && lat != null && lon != null) {
                    if (stopId != null) {
                        viewModelScope.launch {
                            val stop = stopRepository.getStopById(stopId)
                            val resolved = stop?.let { ItineraryEndpoint.FromStop(it) }
                                ?: ItineraryEndpoint.FromAddress(AddressResult(label = label, city = "", lat = lat, lon = lon))
                            updateField(target) { field -> field.copy(query = label, resolved = resolved) }
                        }
                    } else {
                        val resolved = ItineraryEndpoint.FromAddress(AddressResult(label = label, city = "", lat = lat, lon = lon))
                        updateField(target) { field -> field.copy(query = label, resolved = resolved) }
                    }
                }
            }
        }
    }
}

private const val PREFETCH_RADIUS_M = 700.0
private const val PREFETCH_MAX_STOPS = 6
private const val PREFETCH_DURATION_MS = 3 * 60_000L
private val PARIS = TimeZone.of("Europe/Paris")

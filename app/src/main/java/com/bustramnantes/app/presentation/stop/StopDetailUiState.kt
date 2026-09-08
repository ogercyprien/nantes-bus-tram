package com.bustramnantes.app.presentation.stop

import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.util.StopDisruptions

/**
 * Single state holder for [StopDetailViewModel] - one `UiState` per screen, exposed via
 * `StateFlow`. Covers both the common "real stop" path and the best-effort "address" path (see
 * class doc on [StopDetailViewModel]).
 */
data class StopDetailUiState(
    val stopId: String,
    val isLoadingStop: Boolean = true,
    val stop: Stop? = null,
    val stopNotFound: Boolean = false,

    /**
     * Lines serving this stop, resolved from the local cache - shown as colored
     * badges under the stop name and available offline (from [Stop.linkedLines]).
     */
    val stopLines: List<Line> = emptyList(),

    /** `true` when this sheet was opened for arbitrary coordinates rather than a resolved [Stop]. */
    val addressMode: Boolean = false,
    val addressLabel: String? = null,
    val addressLat: Double? = null,
    val addressLon: Double? = null,

    /** From the optional `lineId`/`direction` nav args - pre-focuses a section in the departures list. */
    val focusedLineId: String? = null,
    val focusedDirection: String? = null,

    val isLoadingDepartures: Boolean = true,
    val departureGroups: List<DepartureGroup> = emptyList(),
    val departuresErrorMessage: String? = null,
    val lastUpdatedAtEpochMs: Long? = null,
    /**
     * The board was composed without SIRI (GTFS-RT overlay / timetable, while the shared real-time
     * budget is spent or offline): shown as a discreet "provisoire" note until the next SIRI slot.
     */
    val isProvisional: Boolean = false,
    /** What the board's real-time state is, for the line above the departures (see [RealtimeStatus]). */
    val realtimeStatus: RealtimeStatus? = null,

    /** Disruption notices in force (or starting soon) at this stop or on its lines. */
    val disruptions: StopDisruptions = StopDisruptions.NONE,
    /** Lines with one of [disruptions] in force now - an amber dot on their badges. */
    val disruptedLineIds: Set<String> = emptySet(),
    /** The line catalog by id, for the badges of each notice. */
    val linesById: Map<String, Line> = emptyMap(),
    /** The notice opened in a dialog, if any. */
    val openDisruption: Disruption? = null,

    val favoritesForStop: List<Favorite> = emptyList(),
    val showFavoriteDialog: Boolean = false,
    val isSavingFavorite: Boolean = false,
) {
    val displayName: String get() = stop?.name ?: addressLabel ?: "Arrêt"

    val hasAnyFavorite: Boolean get() = favoritesForStop.isNotEmpty()

    val displayLat: Double? get() = stop?.lat ?: addressLat
    val displayLon: Double? get() = stop?.lon ?: addressLon

    /** Whether the departures section should render at all (address-only mode has none). */
    val showDeparturesSection: Boolean get() = !addressMode
}

/** The real-time state of a Stop Detail board, as told to the rider above the departures. */
sealed interface RealtimeStatus {
    /** Fresh SIRI data (this or the previous 30 s slot). */
    data class Live(val fetchedAtMs: Long) : RealtimeStatus

    /** A cached SIRI snapshot a few minutes old; [refreshing] when fresher data is on its way. */
    data class Cached(val fetchedAtMs: Long, val refreshing: Boolean) : RealtimeStatus

    /** Timetable for now; the real-time data is being fetched (next shared slot). */
    data object Fetching : RealtimeStatus

    /** Okina just failed (offline, rate-limited by another device on the same IP): timetable only. */
    data object Unavailable : RealtimeStatus

    /** A stop real time doesn't cover (a bac landing): the timetable is the board. */
    data object TimetableOnly : RealtimeStatus
}

/** User intents for [StopDetailViewModel.onEvent] - events enter via onEvent(intent). */
sealed interface StopDetailEvent {
    /** Star icon tapped in the actions row. */
    data object FavoriteActionClicked : StopDetailEvent

    /** A specific granularity chosen (or re-chosen to remove) inside [FavoriteGranularityDialog]. */
    data class FavoriteGranularityChosen(
        val type: FavoriteType,
        val lineId: String?,
        val lineShortName: String?,
        val lineColor: String?,
        val lineTextColor: String?,
        val direction: String?,
        val directionName: String?,
    ) : StopDetailEvent

    data object DismissFavoriteDialog : StopDetailEvent
    data object PlanFromHereClicked : StopDetailEvent
    data object PlanToHereClicked : StopDetailEvent
    data object RetryDeparturesClicked : StopDetailEvent

    /** "Voir sur la carte" - show this stop on the map with the sheet lowered to a peek. */
    data object ShowOnMapClicked : StopDetailEvent

    /** A disruption notice tapped - opens it in a dialog. */
    data class DisruptionClicked(val disruption: Disruption) : StopDetailEvent

    data object DismissDisruption : StopDetailEvent

    /** "Horaires théoriques" tapped on a line+direction row - open its full scheduled timetable. */
    data class TimetableClicked(val group: DepartureGroup) : StopDetailEvent
}

/** One-shot side effects, delivered via SharedFlow. */
sealed interface StopDetailEffect {
    data class NavigateToSearch(val route: String) : StopDetailEffect
    data class ShowMessage(val message: String) : StopDetailEffect

    /** Open the map centered on this stop (preselected + search bar prefilled), sheet lowered. */
    data class ShowOnMap(val stopId: String, val stopName: String) : StopDetailEffect

    /** Open the theoretical-timetable screen for a line+direction at this stop (today's date). */
    data class OpenTimetable(
        val stopId: String,
        val lineNumber: String,
        val directionId: Int,
        val stopName: String,
    ) : StopDetailEffect
}

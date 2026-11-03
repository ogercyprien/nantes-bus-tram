package com.bustramnantes.app.presentation.itinerary

import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.presentation.common.navigation.ItineraryTimeMode
import kotlinx.datetime.LocalDate

/**
 * MVI state for [ItineraryResultsScreen] - a single `UiState` sealed class via `StateFlow`.
 * [Success.selectedJourneyIndex] doubles as the "which screen is showing" flag:
 * `null` shows the results list, non-null shows that journey's step-by-step detail view
 * (tapping a journey shows the step-by-step detail view). There is no separate
 * nav-graph destination for the detail view; it is an in-place content swap owned entirely by this
 * screen, since `itinerary_results` is the only route the fixed nav contract allocates here.
 */
sealed interface ItineraryResultsUiState {
    data object Loading : ItineraryResultsUiState

    data class Error(val message: String) : ItineraryResultsUiState

    /** No journeys found for this from/to pair (still a successful API call). */
    data object Empty : ItineraryResultsUiState

    data class Success(
        val journeys: List<Journey>,
        val selectedJourneyIndex: Int? = null,
        /**
         * Pre-built "Horaires théoriques" nav routes per transit leg, filled in *after* the results
         * render. A leg present here shows the button (its boarding stop is known
         * and its direction resolved); absent legs (or before resolution completes) show none.
         */
        val timetableRoutes: Map<JourneySection.Transit, String> = emptyMap(),
        /**
         * Optional one-line explanation shown above the results list - set when a *scheduled*
         * (partir à / arriver à) search returned only walking options, i.e. the installed timetable
         * has no service for the chosen date. `null` the rest of the time.
         */
        val scheduleNotice: String? = null,
        /**
         * Set when the phantom-bus check dropped journeys riding a suspected phantom (and replaced
         * them), or when every option found rides one. `null` the rest of the time.
         */
        val phantomNotice: String? = null,
        /**
         * These are the timetable journeys; the real-time check (phantom buses, predicted times) is
         * still running - the screen shows "Vérification des données temps réel…" until the checked
         * results replace them.
         */
        val realtimeChecking: Boolean = false,
        /**
         * The journey whose route stands out on the results map (drawn wider and on top, shown in the
         * collapsed sheet): set by tapping its line on the map, or by opening its detail; `null` = all
         * routes alike. Distinct from [selectedJourneyIndex], which opens the detail view.
         */
        val highlightedJourneyIndex: Int? = null,
    ) : ItineraryResultsUiState
}

/**
 * Disruption notices for the shown journeys, per transit leg (see `forLeg`: the leg's line, or its
 * boarding/alighting stop, while it runs). Kept beside [ItineraryResultsUiState] (filled after the
 * results render, updated when the notices refresh).
 */
data class ItineraryDisruptionsUiState(
    val byLeg: Map<JourneySection.Transit, List<Disruption>> = emptyMap(),
    val linesById: Map<String, Line> = emptyMap(),
    val openDisruption: Disruption? = null,
)

/** User intents for [ItineraryResultsScreen] - user events enter via `onEvent(intent)`. */
sealed interface ItineraryResultsEvent {
    /** Re-runs the search - offered from the error state. */
    data object Retry : ItineraryResultsEvent

    /** Opens the step-by-step detail view for the journey at [index]. */
    data class SelectJourney(val index: Int) : ItineraryResultsEvent

    /** A route tapped on the results map ([index] = one of its journeys), or empty map tapped (null). */
    data class HighlightJourney(val index: Int?) : ItineraryResultsEvent

    /** Returns from the detail view to the results list. */
    data object CloseJourneyDetail : ItineraryResultsEvent

    /** "Arriver plus tôt": same search, 15 min earlier, same time mode. */
    data object ShiftEarlier : ItineraryResultsEvent

    /** "Arriver plus tard": same search, 15 min later ("maintenant" becomes "Partir à" now + 15). */
    data object ShiftLater : ItineraryResultsEvent

    /** A leg's disruption notice tapped - opens it in a dialog. */
    data class DisruptionClicked(val disruption: Disruption) : ItineraryResultsEvent

    data object DismissDisruption : ItineraryResultsEvent
}

/**
 * The "Arriver plus tôt / plus tard" bar above the results. Kept out of [ItineraryResultsUiState]
 * so it stays put (and tappable again) while a shifted search is loading or came back empty.
 *
 * @property timeLabel the time currently searched, e.g. "Partir à 10:32" / "Arriver à 21:20".
 * @property canShiftEarlier `false` for "maintenant" (no instant to move back from) or when 15 min
 * earlier would leave the date picker's window.
 * @property canShiftLater `false` only past the date picker's forward cap.
 */
data class ItineraryTimeShiftUiState(
    val timeLabel: String,
    val canShiftEarlier: Boolean,
    val canShiftLater: Boolean,
)

/** One-shot effects of [ItineraryViewModel] for the screen's host (the nav graph). */
sealed interface ItineraryResultsEffect {
    /**
     * The searched time was shifted in place. Carried in the nav contract's own encoding
     * ([ItineraryTimeMode] + ISO-8601 `LocalDateTime` string, as in
     * [com.bustramnantes.app.presentation.common.navigation.Screen.ItineraryResults]'s
     * `timeMode`/`departureAt` args) so the graph can hand it to the Search form underneath without
     * touching domain types. Never [ItineraryTimeMode.NOW]: a shift always pins an instant.
     */
    data class SearchTimeShifted(val timeMode: ItineraryTimeMode, val departureAt: String) : ItineraryResultsEffect
}

/**
 * "Partir maintenant" / "Partir à 10:32" / "Arriver à 21:20" - the date is appended
 * (" le 11/10") only when it isn't [today], which is the common case after a midnight rollover.
 */
internal fun formatTimePreference(preference: ItineraryTimePreference, today: LocalDate): String {
    val at = preference.scheduledDateTime ?: return "Partir maintenant"
    val verb = if (preference is ItineraryTimePreference.ArriveBy) "Arriver" else "Partir"
    val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    val day = if (at.date == today) {
        ""
    } else {
        " le ${at.dayOfMonth.toString().padStart(2, '0')}/${at.monthNumber.toString().padStart(2, '0')}"
    }
    return "$verb à $time$day"
}

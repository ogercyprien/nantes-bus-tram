package com.bustramnantes.app.presentation.search

import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.RecentSearch
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.usecase.search.StopSearchResult
import com.bustramnantes.app.presentation.common.navigation.ItineraryTimeMode
import com.bustramnantes.app.presentation.common.navigation.SearchPrefillTarget
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Top-level segmented-control mode ("Arrêt" / "Itinéraire"). */
enum class SearchTopMode { STOP, ITINERARY }

/**
 * Itinéraire "Partir maintenant" / "Partir à" / "Arriver à" choice - see
 * [com.bustramnantes.app.domain.model.ItineraryTimePreference].
 */
enum class DepartureTimeMode { NOW, DEPART_AT, ARRIVE_BY }

/**
 * This screen's mode -> the nav contract's. The two enums are kept separate (rather than the search
 * UI reusing [ItineraryTimeMode]) so the route contract and the screen state can evolve apart, as
 * with [com.bustramnantes.app.presentation.common.navigation.SearchPrefillMode].
 */
fun DepartureTimeMode.toNavTimeMode(): ItineraryTimeMode = when (this) {
    DepartureTimeMode.NOW -> ItineraryTimeMode.NOW
    DepartureTimeMode.DEPART_AT -> ItineraryTimeMode.DEPART_AT
    DepartureTimeMode.ARRIVE_BY -> ItineraryTimeMode.ARRIVE_BY
}

/**
 * Sub-mode within the Arrêt segment. Adresse mode is not a third segmented-control
 * option - it is reached from Arrêt mode via the "Rechercher une adresse" button and returns to it.
 */
enum class StopAddressSubMode { STOP, ADDRESS }

/**
 * A resolved Départ/Arrivée endpoint - either a [Stop] or a free [AddressResult],
 * normalized to the label/lat/lon/stopId shape [Screen.ItineraryResults.createRoute] needs.
 */
sealed class ItineraryEndpoint {
    abstract val label: String
    abstract val lat: Double
    abstract val lon: Double
    abstract val stopId: String?

    data class FromStop(val stop: Stop) : ItineraryEndpoint() {
        override val label: String get() = stop.name
        override val lat: Double get() = stop.lat
        override val lon: Double get() = stop.lon
        override val stopId: String get() = stop.id
    }

    data class FromAddress(val address: AddressResult) : ItineraryEndpoint() {
        override val label: String get() = address.label
        override val lat: Double get() = address.lat
        override val lon: Double get() = address.lon
        override val stopId: String? get() = null
    }
}

/** State of one Itinéraire-mode field (Départ or Arrivée). */
data class ItineraryFieldUiState(
    val target: SearchPrefillTarget,
    val label: String,
    val query: String = "",
    val resolved: ItineraryEndpoint? = null,
    val isSearchingAddress: Boolean = false,
    val stopSuggestions: List<Stop> = emptyList(),
    val addressSuggestions: List<AddressResult> = emptyList(),
)

/** Single source of truth backing [SearchScreen]. */
data class SearchUiState(
    val topMode: SearchTopMode = SearchTopMode.STOP,

    /** Last day of the installed timetable - the departure date picker stops there. */
    val scheduleFeedEnd: LocalDate? = null,

    // Arrêt / Adresse
    val stopAddressSubMode: StopAddressSubMode = StopAddressSubMode.STOP,
    val stopQuery: String = "",
    val addressQuery: String = "",
    val isAddressSearching: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val favoriteStopResults: List<StopSearchResult> = emptyList(),
    val stopResults: List<StopSearchResult> = emptyList(),
    val addressResults: List<AddressResult> = emptyList(),

    // Itinéraire
    val departField: ItineraryFieldUiState = ItineraryFieldUiState(
        target = SearchPrefillTarget.DEPART,
        label = "Départ",
    ),
    val arriveField: ItineraryFieldUiState = ItineraryFieldUiState(
        target = SearchPrefillTarget.ARRIVE,
        label = "Arrivée",
    ),
    val activeItineraryField: SearchPrefillTarget? = null,
    val departureTimeMode: DepartureTimeMode = DepartureTimeMode.NOW,
    /**
     * The instant [departureTimeMode] pins - a departure for `DEPART_AT`, an arrival deadline for
     * `ARRIVE_BY`. Always `null` for `NOW`.
     */
    val departureDateTime: LocalDateTime? = null,
    val isDepartureTimePickerOpen: Boolean = false,
) {
    val canSearchJourneys: Boolean
        get() = departField.resolved != null && arriveField.resolved != null
}

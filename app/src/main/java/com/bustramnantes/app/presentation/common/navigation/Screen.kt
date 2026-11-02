package com.bustramnantes.app.presentation.common.navigation

import android.net.Uri
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.datetime.LocalDate

/**
 * Fixed navigation contract shared by every feature module. Route strings/arg names here are
 * load-bearing - every screen agent navigates using these helpers instead of hand-concatenating
 * strings, so the graph in [AppNavGraph] and every `navController.navigate(...)` call agree.
 *
 * Two kinds of route string are exposed per destination:
 *  - `ROUTE_PATTERN` (or the plain `route` for arg-less screens): the templated pattern registered
 *    with `NavHost`/`composable(route = …)`, e.g. `"search?prefillMode={prefillMode}&…"`.
 *  - `createRoute(...)`: builds the *concrete* string passed to `navController.navigate(...)`,
 *    e.g. `"search?prefillMode=STOP"` (only the args actually supplied are included).
 */
// Route patterns are declared at file scope (not as members of their own `data object`) because a
// sealed subtype's supertype constructor call is evaluated before its own body, so it cannot
// reference a `const val` declared inside that same object.
private const val SEARCH_ROUTE_PATTERN = "search?prefillMode={prefillMode}&prefillStopId={prefillStopId}" +
    "&prefillLabel={prefillLabel}&prefillLat={prefillLat}&prefillLon={prefillLon}" +
    "&prefillTarget={prefillTarget}"
private const val STOP_DETAIL_ROUTE_PATTERN = "stop_detail/{stopId}?lineId={lineId}&direction={direction}" +
    "&addressLabel={addressLabel}&addressLat={addressLat}&addressLon={addressLon}&lowered={lowered}" +
    "&editFavoriteId={editFavoriteId}"
private const val ITINERARY_RESULTS_ROUTE_PATTERN = "itinerary_results?fromLabel={fromLabel}&fromLat={fromLat}" +
    "&fromLon={fromLon}&fromStopId={fromStopId}&toLabel={toLabel}&toLat={toLat}" +
    "&toLon={toLon}&toStopId={toStopId}&timeMode={timeMode}&departureAt={departureAt}"
private const val TIMETABLE_ROUTE_PATTERN =
    "timetable/{stopId}/{lineNumber}/{directionId}?date={date}&stopName={stopName}&highlight={highlight}"

sealed class Screen(val route: String) {

    /** Bottom-nav tab - Home dashboard. */
    data object Home : Screen("home")

    /** Bottom-nav tab - stops closest to the user's current location ("À proximité"). */
    data object Nearby : Screen("nearby")

    /** Bottom-nav tab - unified Search. */
    data object Search : Screen(SEARCH_ROUTE_PATTERN) {
        const val BASE_ROUTE = "search"
        const val ROUTE_PATTERN = SEARCH_ROUTE_PATTERN

        val arguments: List<NamedNavArgument> = listOf(
            navArgument("prefillMode") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("prefillStopId") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("prefillLabel") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("prefillLat") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("prefillLon") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("prefillTarget") { type = NavType.StringType; nullable = true; defaultValue = null },
        )

        /** Builds a concrete navigate() route. All params are optional; omitted ones are left out of the query string. */
        fun createRoute(
            prefillMode: SearchPrefillMode? = null,
            prefillStopId: String? = null,
            prefillLabel: String? = null,
            prefillLat: Double? = null,
            prefillLon: Double? = null,
            prefillTarget: SearchPrefillTarget? = null,
        ): String {
            val params = buildList {
                prefillMode?.let { add("prefillMode" to it.name) }
                prefillStopId?.let { add("prefillStopId" to it) }
                prefillLabel?.let { add("prefillLabel" to it) }
                prefillLat?.let { add("prefillLat" to it.toString()) }
                prefillLon?.let { add("prefillLon" to it.toString()) }
                prefillTarget?.let { add("prefillTarget" to it.name) }
            }
            if (params.isEmpty()) return BASE_ROUTE
            val query = params.joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
            return "$BASE_ROUTE?$query"
        }

        fun prefillModeFrom(entry: NavBackStackEntry): SearchPrefillMode? =
            entry.arguments?.getString("prefillMode")?.let { runCatching { SearchPrefillMode.valueOf(it) }.getOrNull() }

        fun prefillStopIdFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("prefillStopId")

        fun prefillLabelFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("prefillLabel")

        fun prefillLatFrom(entry: NavBackStackEntry): Double? = entry.arguments?.getString("prefillLat")?.toDoubleOrNull()

        fun prefillLonFrom(entry: NavBackStackEntry): Double? = entry.arguments?.getString("prefillLon")?.toDoubleOrNull()

        fun prefillTargetFrom(entry: NavBackStackEntry): SearchPrefillTarget? =
            entry.arguments?.getString("prefillTarget")?.let { runCatching { SearchPrefillTarget.valueOf(it) }.getOrNull() }

        /**
         * Result keys on the Search back-stack entry's [androidx.lifecycle.SavedStateHandle]: when
         * the itinerary results screen shifts the searched time in place ("Arriver plus tôt / plus
         * tard"), it writes the new [ItineraryTimeMode] name and ISO-8601 `LocalDateTime` here (same
         * encodings as [ItineraryResults]' `timeMode`/`departureAt` args). The Search destination
         * consumes them when it comes back on screen, so the form shows the shifted time after back.
         * Last write wins - only the final shift matters.
         */
        const val ITINERARY_TIME_MODE_RESULT_KEY = "itineraryTimeModeResult"

        /** See [ITINERARY_TIME_MODE_RESULT_KEY]. Written last; its presence triggers the update. */
        const val ITINERARY_DEPARTURE_AT_RESULT_KEY = "itineraryDepartureAtResult"
    }

    /** Bottom-nav tab - network Map. */
    data object Map : Screen("map") {
        /**
         * Key under which "Voir sur la carte" writes the stop id to preselect, on the Map back-stack
         * entry's [androidx.lifecycle.SavedStateHandle]. Passed this way (rather than as a route arg)
         * so the tab's route string stays a plain `"map"` for the bottom-nav/last-tab plumbing.
         */
        const val PRESELECT_STOP_ID_KEY = "preselectStopId"
    }

    /** Pushed from Home's top-right icon. Normal back-stack destination. */
    data object Settings : Screen("settings")

    /** Every disruption notice on the network (Home's "Perturbations" action). */
    data object Disruptions : Screen("disruptions")

    /**
     * Stop Detail bottom sheet. Registered via the `dialog()` NavGraphBuilder
     * extension so it overlays like a modal bottom sheet from any tab. `lineId`/`direction` are
     * optional and only used to pre-focus a line/direction in the departures list.
     */
    data object StopDetail : Screen(STOP_DETAIL_ROUTE_PATTERN) {
        const val ROUTE_PATTERN = STOP_DETAIL_ROUTE_PATTERN

        val arguments: List<NamedNavArgument> = listOf(
            navArgument("stopId") { type = NavType.StringType },
            navArgument("lineId") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("direction") { type = NavType.StringType; nullable = true; defaultValue = null },
            // Best-effort address support (Search's Adresse mode has no `Stop` row
            // to reference). See presentation/search/SearchNavigation.kt's NAV_CONTRACT doc comment.
            navArgument("addressLabel") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("addressLat") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("addressLon") { type = NavType.StringType; nullable = true; defaultValue = null },
            // "true" when opened via "Voir sur la carte": the sheet opens at a low peek over the map
            // (draggable up) instead of expanded. See StopDetailBottomSheet.
            navArgument("lowered") { type = NavType.StringType; nullable = true; defaultValue = null },
            // Home's "Modifier": the sheet opens with this favorite's editor already up.
            navArgument("editFavoriteId") { type = NavType.StringType; nullable = true; defaultValue = null },
        )

        fun createRoute(
            stopId: String,
            lineId: String? = null,
            direction: String? = null,
            addressLabel: String? = null,
            addressLat: Double? = null,
            addressLon: Double? = null,
            lowered: Boolean = false,
            editFavoriteId: String? = null,
        ): String {
            val base = "stop_detail/${Uri.encode(stopId)}"
            val params = buildList {
                lineId?.let { add("lineId" to it) }
                direction?.let { add("direction" to it) }
                addressLabel?.let { add("addressLabel" to it) }
                addressLat?.let { add("addressLat" to it.toString()) }
                addressLon?.let { add("addressLon" to it.toString()) }
                if (lowered) add("lowered" to "true")
                editFavoriteId?.let { add("editFavoriteId" to it) }
            }
            if (params.isEmpty()) return base
            val query = params.joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
            return "$base?$query"
        }

        fun stopIdFrom(entry: NavBackStackEntry): String = entry.arguments?.getString("stopId").orEmpty()

        fun lineIdFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("lineId")

        fun directionFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("direction")

        fun addressLabelFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("addressLabel")

        fun addressLatFrom(entry: NavBackStackEntry): Double? =
            entry.arguments?.getString("addressLat")?.toDoubleOrNull()

        fun addressLonFrom(entry: NavBackStackEntry): Double? =
            entry.arguments?.getString("addressLon")?.toDoubleOrNull()

        fun loweredFrom(entry: NavBackStackEntry): Boolean = entry.arguments?.getString("lowered") == "true"
    }

    /**
     * Itinerary results, pushed from Search's "Chercher" button in Itinéraire mode.
     * `fromStopId`/`toStopId` are only present when the corresponding endpoint is a stop
     * rather than a free address: the planner then starts/ends at that stop's quays with no walk.
     *
     * The travel time is carried by **two** args: `timeMode` ([ItineraryTimeMode]) says which end
     * of the trip is pinned, and `departureAt` carries the instant it is pinned to. They are kept
     * separate (rather than one encoded string) so an absent/garbled timestamp degrades to
     * "maintenant" instead of silently changing the meaning of the search.
     */
    data object ItineraryResults : Screen(ITINERARY_RESULTS_ROUTE_PATTERN) {
        const val ROUTE_PATTERN = ITINERARY_RESULTS_ROUTE_PATTERN

        val arguments: List<NamedNavArgument> = listOf(
            navArgument("fromLabel") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("fromLat") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("fromLon") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("fromStopId") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("toLabel") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("toLat") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("toLon") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("toStopId") { type = NavType.StringType; nullable = true; defaultValue = null },
            // [ItineraryTimeMode] name - `null`/absent means NOW (so old-shaped routes still work).
            navArgument("timeMode") { type = NavType.StringType; nullable = true; defaultValue = null },
            // Kotlinx `LocalDateTime.toString()` (ISO-8601, e.g. "2026-07-03T08:00:00"): the instant
            // `timeMode` pins - a departure for DEPART_AT, an arrival deadline for ARRIVE_BY, and
            // `null` for NOW.
            navArgument("departureAt") { type = NavType.StringType; nullable = true; defaultValue = null },
        )

        fun createRoute(
            fromLabel: String,
            fromLat: Double,
            fromLon: Double,
            toLabel: String,
            toLat: Double,
            toLon: Double,
            fromStopId: String? = null,
            toStopId: String? = null,
            timeMode: ItineraryTimeMode = ItineraryTimeMode.NOW,
            departureAt: String? = null,
        ): String {
            val params = buildList {
                add("fromLabel" to fromLabel)
                add("fromLat" to fromLat.toString())
                add("fromLon" to fromLon.toString())
                fromStopId?.let { add("fromStopId" to it) }
                add("toLabel" to toLabel)
                add("toLat" to toLat.toString())
                add("toLon" to toLon.toString())
                toStopId?.let { add("toStopId" to it) }
                if (timeMode != ItineraryTimeMode.NOW) add("timeMode" to timeMode.name)
                departureAt?.let { add("departureAt" to it) }
            }
            val query = params.joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
            return "$BASE_ROUTE?$query"
        }

        private const val BASE_ROUTE = "itinerary_results"

        fun fromLabelFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("fromLabel")
        fun fromLatFrom(entry: NavBackStackEntry): Double? = entry.arguments?.getString("fromLat")?.toDoubleOrNull()
        fun fromLonFrom(entry: NavBackStackEntry): Double? = entry.arguments?.getString("fromLon")?.toDoubleOrNull()
        fun fromStopIdFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("fromStopId")
        fun toLabelFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("toLabel")
        fun toLatFrom(entry: NavBackStackEntry): Double? = entry.arguments?.getString("toLat")?.toDoubleOrNull()
        fun toLonFrom(entry: NavBackStackEntry): Double? = entry.arguments?.getString("toLon")?.toDoubleOrNull()
        fun toStopIdFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("toStopId")
        /** Unknown/absent values fall back to [ItineraryTimeMode.NOW]. */
        fun timeModeFrom(entry: NavBackStackEntry): ItineraryTimeMode =
            parseTimeMode(entry.arguments?.getString("timeMode"))

        fun departureAtFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("departureAt")

        /** Shared by [timeModeFrom] and by `ItineraryViewModel`, which reads a `SavedStateHandle`. */
        fun parseTimeMode(raw: String?): ItineraryTimeMode =
            ItineraryTimeMode.entries.firstOrNull { it.name == raw } ?: ItineraryTimeMode.NOW
    }

    /**
     * Theoretical (scheduled) timetable for one line+direction at a stop, reachable from the Stop
     * Detail sheet and from an itinerary transit leg. A
     * full-screen route (not a nested sheet). The three path args address the timetable
     * (`{stopId}/{lineNumber}/{directionId}`, direction 1-based); `date` (ISO `yyyy-MM-dd`, null → today), `stopName`
     * (header), and `highlight` (`HH:mm`, only from an itinerary leg - the grid emphasises that
     * departure) are optional query args. Line identity + direction name are re-resolved from the
     * line cache by `lineNumber`/`directionId`, so they aren't passed here.
     */
    data object Timetable : Screen(TIMETABLE_ROUTE_PATTERN) {
        const val ROUTE_PATTERN = TIMETABLE_ROUTE_PATTERN

        val arguments: List<NamedNavArgument> = listOf(
            navArgument("stopId") { type = NavType.StringType },
            navArgument("lineNumber") { type = NavType.StringType },
            navArgument("directionId") { type = NavType.StringType },
            navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("stopName") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("highlight") { type = NavType.StringType; nullable = true; defaultValue = null },
        )

        fun createRoute(
            stopId: String,
            lineNumber: String,
            directionId: Int,
            date: LocalDate? = null,
            stopName: String? = null,
            highlight: String? = null,
        ): String {
            val base = "timetable/${Uri.encode(stopId)}/${Uri.encode(lineNumber)}/$directionId"
            val params = buildList {
                date?.let { add("date" to it.toString()) }
                stopName?.let { add("stopName" to it) }
                highlight?.let { add("highlight" to it) }
            }
            if (params.isEmpty()) return base
            val query = params.joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
            return "$base?$query"
        }

        fun stopIdFrom(entry: NavBackStackEntry): String = entry.arguments?.getString("stopId").orEmpty()
        fun lineNumberFrom(entry: NavBackStackEntry): String = entry.arguments?.getString("lineNumber").orEmpty()
        fun directionIdFrom(entry: NavBackStackEntry): Int? = entry.arguments?.getString("directionId")?.toIntOrNull()
        fun dateFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("date")
        fun stopNameFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("stopName")
        fun highlightFrom(entry: NavBackStackEntry): String? = entry.arguments?.getString("highlight")
    }

}

/** `prefillMode` values for [Screen.Search.createRoute] (nav contract). */
enum class SearchPrefillMode {
    STOP,
    ADDRESS,
    ITINERARY,
}

/**
 * `timeMode` values for [Screen.ItineraryResults.createRoute] - which end of the trip the
 * `departureAt` arg pins. Mirrors the domain's
 * [com.bustramnantes.app.domain.model.ItineraryTimePreference]; the nav contract keeps its own
 * enum so the graph stays free of domain types, exactly like [SearchPrefillMode] does.
 */
enum class ItineraryTimeMode {
    NOW,
    DEPART_AT,
    ARRIVE_BY,
}

/** `prefillTarget` values for [Screen.Search.createRoute] - which itinerary field to pre-fill. */
enum class SearchPrefillTarget {
    DEPART,
    ARRIVE,
}

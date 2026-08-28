package com.bustramnantes.app.presentation.common.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import com.bustramnantes.app.presentation.disruptions.DisruptionsScreen
import com.bustramnantes.app.presentation.home.HomeScreen
import com.bustramnantes.app.presentation.itinerary.ItineraryResultsScreen
import com.bustramnantes.app.presentation.map.MapScreen
import com.bustramnantes.app.presentation.nearby.NearbyStopsScreen
import com.bustramnantes.app.presentation.search.SearchScreen
import com.bustramnantes.app.presentation.search.SearchViewModel
import com.bustramnantes.app.presentation.settings.SettingsScreen
import com.bustramnantes.app.presentation.stop.StopDetailBottomSheet
import com.bustramnantes.app.presentation.timetable.TimetableScreen

/**
 * Structurally-complete navigation graph implementing the fixed contract in [Screen]. Hosts the
 * real screen composable at every destination, including the `stop_detail` modal-sheet-over-dialog
 * overlay reachable from any tab.
 *
 * @param startDestination one of the [Screen] route constants - the caller (integration agent)
 * decides between [Screen.Home] and [Screen.Search] based on whether any favorite exists and the
 * last-active-tab preference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSearch = {
                    navController.navigateToTab(Screen.Search.createRoute(prefillMode = SearchPrefillMode.STOP))
                },
                onNavigateToStopDetail = { stopId, lineId, direction ->
                    navController.navigate(Screen.StopDetail.createRoute(stopId, lineId, direction))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToDisruptions = {
                    navController.navigate(Screen.Disruptions.route)
                },
            )
        }

        composable(Screen.Nearby.route) {
            NearbyStopsScreen(
                // Same handoff as a Search result row: open the shared Stop Detail sheet for the stop.
                onNavigateToStopDetail = { stopId ->
                    navController.navigate(Screen.StopDetail.createRoute(stopId))
                },
            )
        }

        composable(Screen.Search.ROUTE_PATTERN, arguments = Screen.Search.arguments) { entry ->
            // Same entry-scoped instance SearchScreen would get on its own; hoisted so the itinerary
            // results' time shifts (left on this entry's SavedStateHandle, see
            // Screen.Search.ITINERARY_TIME_MODE_RESULT_KEY) can be applied to the form on return.
            val searchViewModel: SearchViewModel = hiltViewModel()
            val handle = entry.savedStateHandle
            val shiftedAt by handle.getStateFlow<String?>(Screen.Search.ITINERARY_DEPARTURE_AT_RESULT_KEY, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(shiftedAt) {
                val departureAt = shiftedAt ?: return@LaunchedEffect
                searchViewModel.onItineraryTimeShiftedInResults(
                    timeMode = Screen.ItineraryResults.parseTimeMode(
                        handle.get<String>(Screen.Search.ITINERARY_TIME_MODE_RESULT_KEY),
                    ),
                    departureAt = departureAt,
                )
                handle.remove<String>(Screen.Search.ITINERARY_TIME_MODE_RESULT_KEY)
                handle.remove<String>(Screen.Search.ITINERARY_DEPARTURE_AT_RESULT_KEY)
            }
            SearchScreen(onNavigate = navController::navigate, viewModel = searchViewModel)
        }

        composable(Screen.Map.route) {
            MapScreen(navController = navController)
        }

        composable(Screen.Disruptions.route) {
            DisruptionsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Full-size Dialog (usePlatformDefaultWidth = false) hosting the Stop Detail
        // ModalBottomSheet, so this destination behaves like a bottom-sheet overlay reachable from
        // any tab. StopDetailBottomSheet owns the ModalBottomSheet itself.
        dialog(
            route = Screen.StopDetail.ROUTE_PATTERN,
            arguments = Screen.StopDetail.arguments,
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
        ) { entry ->
            StopDetailBottomSheet(
                onNavigateToSearch = { route -> navController.navigate(route) },
                onShowOnMap = { stopId, _ -> navController.showStopOnMap(stopId) },
                onOpenTimetable = { effect ->
                    navController.navigate(
                        Screen.Timetable.createRoute(
                            stopId = effect.stopId,
                            lineNumber = effect.lineNumber,
                            directionId = effect.directionId,
                            stopName = effect.stopName,
                        ),
                    )
                },
                onDismiss = { navController.popBackStack() },
                startLowered = Screen.StopDetail.loweredFrom(entry),
            )
        }

        composable(
            Screen.ItineraryResults.ROUTE_PATTERN,
            arguments = Screen.ItineraryResults.arguments,
        ) {
            ItineraryResultsScreen(
                onBackClick = { navController.popBackStack() },
                onStopClick = { stopId ->
                    navController.navigate(Screen.StopDetail.createRoute(stopId))
                },
                onOpenTimetable = { route -> navController.navigate(route) },
                // In-place "Arriver plus tôt / plus tard": hand the new time back to the Search entry
                // underneath (consumed when it resumes - see the Search destination above).
                onSearchTimeShifted = { timeMode, departureAt ->
                    navController.previousBackStackEntry?.savedStateHandle?.let { handle ->
                        handle[Screen.Search.ITINERARY_TIME_MODE_RESULT_KEY] = timeMode.name
                        handle[Screen.Search.ITINERARY_DEPARTURE_AT_RESULT_KEY] = departureAt
                    }
                },
            )
        }

        composable(Screen.Timetable.ROUTE_PATTERN, arguments = Screen.Timetable.arguments) {
            TimetableScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

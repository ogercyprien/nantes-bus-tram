package com.bustramnantes.app.presentation.common.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Switches to one of the 3 bottom-nav tabs: pops up to the graph's start destination
 * and `launchSingleTop` so repeated taps don't stack duplicates. Tapping a tab therefore always
 * lands on that tab's own screen.
 *
 * Deliberately does NOT use `saveState`/`restoreState`. Those only work cleanly when every
 * destination on a tab's stack belongs to that tab; here cross-tab detours (e.g. Map → Stop Detail
 * → "Partir d'ici" → Search) stack on top of whichever tab was active, so `restoreState` would
 * revive that whole sub-stack when you tapped a tab - e.g. tapping "Plan" after that flow restored
 * the Search screen instead of the map, which read as "the tab does nothing". Landing on the tab
 * root is predictable; the map camera and lists are cheap to recompute.
 *
 * Used both by [com.bustramnantes.app.presentation.common.components.BottomNavigationBar]'s tap
 * handler (wired in `MainActivity`) and by in-graph destinations that navigate to a tab
 * programmatically (e.g. Home's empty-state "Recherche" CTA).
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}

/**
 * "Voir sur la carte" from a Stop Detail *dialog* sheet (opened over Home/Search/Itinerary): closes
 * that dialog and lands the user on the Map, which then opens its own **in-screen** Stop Detail sheet
 * for [stopId] (preselected marker + search bar prefilled). Because the map's sheet is a non-modal
 * `BottomSheetScaffold`, the map stays interactive beneath it.
 *
 * (1) drops the current dialog sheet, (2) lands on a single fresh Map entry (pops any existing map so
 * we don't stack duplicates), (3) hands that entry the stop id to preselect via its
 * [androidx.lifecycle.SavedStateHandle] (read by `MapViewModel`, which drives the map's sheet).
 */
fun NavController.showStopOnMap(stopId: String) {
    popBackStack()
    navigate(Screen.Map.route) {
        popUpTo(Screen.Map.route) { inclusive = true }
        launchSingleTop = true
    }
    getBackStackEntry(Screen.Map.route).savedStateHandle[Screen.Map.PRESELECT_STOP_ID_KEY] = stopId
}

package com.bustramnantes.app.presentation.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.presentation.common.components.OfflineAwareContent
import com.bustramnantes.app.presentation.common.navigation.Screen
import com.bustramnantes.app.presentation.stop.StopDetailEffect
import com.bustramnantes.app.presentation.stop.StopDetailSheetInner
import com.bustramnantes.app.presentation.stop.StopDetailViewModel
import com.bustramnantes.app.presentation.common.utils.NANTES_LATITUDE
import com.bustramnantes.app.presentation.common.utils.NANTES_LONGITUDE
import com.bustramnantes.app.presentation.common.utils.isWithinNantesArea
import com.bustramnantes.app.presentation.map.components.MapSearchBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

private const val DEFAULT_ZOOM = 12.0
private const val FOCUSED_ZOOM = 16.0
private const val CAMERA_ANIM_MS = 800
private const val USER_LOCATION_EASE_MS = 1000
private const val LOCATION_POLL_ATTEMPTS = 6
private const val LOCATION_POLL_DELAY_MS = 500L
/** Half-size (dp) of the padded box used to make tapping a stop marker forgiving. */
private const val TAP_TOLERANCE_DP = 20f
/** Peek height of the in-screen Stop Detail sheet - shows the header + actions + first departures. */
private val STOP_SHEET_PEEK_HEIGHT = 300.dp

/**
 * Full-screen Naolib network map. Embeds a MapLibre Native `MapView` via `AndroidView`
 * with full lifecycle forwarding.
 *
 * Unlike the Home/Search/Itinerary entry points (which open Stop Detail as a `ModalBottomSheet` over
 * a `dialog()` destination), the map hosts the **same sheet body** ([StopDetailSheetInner]) itself,
 * inside a **non-modal [BottomSheetScaffold]**. There is deliberately no scrim, so the map stays
 * pan/zoom-interactive beneath the sheet - the user can drag the sheet to a peek, move the map, and
 * drag it back up. Tapping a stop marker / a search result / a "Voir sur la carte" preselect calls
 * [selectStopOnMap], which re-points the single reused [StopDetailViewModel] at that stop, moves the
 * highlight ring, animates the camera, and raises the sheet from `Hidden` to its peek. Tapping empty
 * map (or dragging the sheet to `Hidden`) dismisses it and stops polling; the highlight ring persists.
 * The map stays full-screen under the sheet (no split layout).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
    stopDetailViewModel: StopDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stopDetailUiState by stopDetailViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // "Voir sur la carte" delivers the stop id to preselect on THIS Map back-stack entry's own
    // SavedStateHandle (see NavController.showStopOnMap). It must be read from the entry's handle -
    // a different instance than the one Hilt injects into MapViewModel, which never sees that write
    // (that mismatch was the "map opens but the sheet doesn't" bug). We observe it here and clear it
    // once consumed so it fires exactly once.
    val mapBackStackEntry = remember(navController) { navController.getBackStackEntry(Screen.Map.route) }
    val preselectStopId by mapBackStackEntry.savedStateHandle
        .getStateFlow<String?>(Screen.Map.PRESELECT_STOP_ID_KEY, null)
        .collectAsStateWithLifecycle()

    // The Stop Detail sheet is hosted *inside* this screen via a non-modal BottomSheetScaffold (no
    // scrim), so the map stays pan/zoom-interactive while the sheet is up - the user can drag the
    // sheet down to a peek, move the map, and drag it back up. Starts Hidden until a stop is picked.
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
        ),
    )

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    // Kept fresh across recomposition so the (long-lived, created-once) MapLibre click listener
    // below always reads the latest stops rather than closing over a stale list.
    val latestStops by rememberUpdatedState(uiState.stops)

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var locationComponentActivated by remember { mutableStateOf(false) }
    // The last stop the user opened, kept highlighted on the map (a colored ring) even after the
    // sheet is dismissed, so it's easy to see which one was just looked up.
    var selectedStop by remember { mutableStateOf<Stop?>(null) }

    // Selects a stop: highlight ring + center camera + re-point the in-screen Stop Detail sheet at it.
    // No navigation - the sheet lives in this screen. The sheet is *raised* by a separate effect keyed
    // on the selected stop id (so it animates only once the sheet content actually exists - see below).
    fun selectStopOnMap(stop: Stop) {
        selectedStop = stop
        stopDetailViewModel.selectStop(stop.id)
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(stop.lat, stop.lon), FOCUSED_ZOOM),
            CAMERA_ANIM_MS,
        )
    }

    // Recenter the camera on the user's last known fix. If permission is missing, request it (the
    // location effect below then activates the component); if the fix isn't available yet, tell the
    // user rather than silently doing nothing.
    fun recenterOnUser() {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            return
        }
        val location = if (locationComponentActivated) mapLibreMap?.locationComponent?.lastKnownLocation else null
        if (location != null) {
            mapLibreMap?.easeCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), FOCUSED_ZOOM),
                USER_LOCATION_EASE_MS,
            )
        } else {
            scope.launch { snackbarHostState.showSnackbar("Position introuvable pour le moment") }
        }
    }

    val mapView = rememberMapViewWithLifecycle()

    // Obtain the MapLibreMap once per MapView instance.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map -> mapLibreMap = map }
    }

    // Build the style (sources/layers) once per map instance, then wire the click listener.
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (style != null) return@LaunchedEffect
        // Move the compass to the bottom-right so it isn't hidden behind the top search bar (the bar
        // spans the full width, so any top-anchored compass would sit under it). The map view's own
        // bottom edge already sits above the bottom nav bar, so bottom-right is clear.
        val density = context.resources.displayMetrics.density
        // Bottom-LEFT: the top edge is under the search bar, and the bottom-right now holds the
        // "recenter on me" FAB, so the compass would collide with it there.
        map.uiSettings.setCompassGravity(android.view.Gravity.BOTTOM or android.view.Gravity.START)
        map.uiSettings.setCompassMargins((12 * density).toInt(), 0, 0, (16 * density).toInt())
        // Start from a minimal base style that declares a glyphs (font) source, otherwise the
        // line-number SymbolLayer has no fonts and its text silently never renders - see
        // MapStyleIds.baseStyleJson. Sources/layers are then added programmatically on top.
        map.setStyle(Style.Builder().fromJson(MapStyleIds.baseStyleJson())) { loadedStyle ->
            // Beige backdrop first (bottom of the stack) so unloaded/offline areas look like a
            // loading map instead of a black void - see MapStyleIds.backgroundLayer.
            loadedStyle.addLayer(MapStyleIds.backgroundLayer())
            loadedStyle.addSource(MapStyleIds.osmRasterSource())
            loadedStyle.addLayer(MapStyleIds.osmRasterLayer())
            // Visible colored line traces: a pre-rendered raster overlay whose parallel-lane
            // separation is baked in upstream (matches the official Naolib map; the vector tiles
            // can't produce non-overlapping lanes) - see MapStyleIds.SOURCE_NETWORK_RASTER.
            loadedStyle.addSource(MapStyleIds.networkRasterSource())
            loadedStyle.addLayer(MapStyleIds.networkRasterLayer())
            // Vector network source kept only for line-number labels drawn along the routes.
            loadedStyle.addSource(MapStyleIds.networkVectorSource())
            loadedStyle.addLayer(MapStyleIds.networkLabelLayer())
            // Selected-stop halo is added BEFORE the stops layer so the white stop dot draws on top
            // of its highlight ring (the ring surrounds the dot rather than covering it).
            loadedStyle.addSource(MapStyleIds.selectedStopSource(selectedStop))
            loadedStyle.addLayer(MapStyleIds.selectedStopLayer())
            loadedStyle.addSource(MapStyleIds.stopsGeoJsonSource(latestStops))
            loadedStyle.addLayer(MapStyleIds.stopsCircleLayer())

            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(NANTES_LATITUDE, NANTES_LONGITUDE), DEFAULT_ZOOM),
            )

            val tapTolerancePx = TAP_TOLERANCE_DP * density
            map.addOnMapClickListener { latLng ->
                val screenPoint = map.projection.toScreenLocation(latLng)
                // Query a padded box (not the exact pixel) so small stop dots are forgiving to tap.
                val tapBox = android.graphics.RectF(
                    screenPoint.x - tapTolerancePx,
                    screenPoint.y - tapTolerancePx,
                    screenPoint.x + tapTolerancePx,
                    screenPoint.y + tapTolerancePx,
                )
                val tappedFeature = map.queryRenderedFeatures(tapBox, MapStyleIds.LAYER_STOPS).firstOrNull()
                val tappedStopId = tappedFeature?.getStringProperty(MapStyleIds.STOP_PROPERTY_ID)
                val tappedStop = tappedStopId?.let { id -> latestStops.firstOrNull { it.id == id } }
                if (tappedStop != null) {
                    selectStopOnMap(tappedStop)
                } else {
                    // Tap on empty map -> dismiss the sheet (reveals the whole map).
                    scope.launch { sheetState.bottomSheetState.hide() }
                }
                true
            }

            style = loadedStyle
        }
    }

    // Keep the stops GeoJSON source in sync with the Room-backed cache.
    LaunchedEffect(style, uiState.stops) {
        style?.getSourceAs<GeoJsonSource>(MapStyleIds.SOURCE_STOPS)
            ?.setGeoJson(MapStyleIds.stopsToFeatureCollection(uiState.stops))
    }

    // Move the highlight ring to whichever stop was last opened (persists after the sheet closes).
    LaunchedEffect(style, selectedStop) {
        style?.getSourceAs<GeoJsonSource>(MapStyleIds.SOURCE_SELECTED_STOP)
            ?.setGeoJson(MapStyleIds.stopsToFeatureCollection(listOfNotNull(selectedStop)))
    }

    // "Voir sur la carte": preselect + open the sheet for the stop the dialog handed us (via this
    // entry's SavedStateHandle, read above). Re-runs once the stops list / map are ready, then clears
    // the key so a recomposition/restore doesn't reopen it.
    LaunchedEffect(preselectStopId, uiState.stops, mapLibreMap) {
        val id = preselectStopId ?: return@LaunchedEffect
        val stop = uiState.stops.firstOrNull { it.id == id } ?: return@LaunchedEffect
        viewModel.onPreselectStop(id)
        selectStopOnMap(stop)
        mapBackStackEntry.savedStateHandle[Screen.Map.PRESELECT_STOP_ID_KEY] = null
    }

    // Raise the sheet to its peek whenever a stop becomes selected. Keyed on the stop id so it runs
    // *after* the sheet content for that stop has been composed (its anchors exist), which the earlier
    // imperative call raced ahead of - leaving the sheet stuck Hidden (a blank map).
    LaunchedEffect(stopDetailUiState.stopId) {
        if (stopDetailUiState.stopId.isNotBlank()) {
            sheetState.bottomSheetState.partialExpand()
        }
    }

    // Stop polling only when the user actually dismisses the sheet (it returns to Hidden *after*
    // having been shown). Guarding on `wasVisible` avoids the initial-Hidden emission racing with an
    // incoming selection and clearing it (which would blank the panel).
    LaunchedEffect(sheetState.bottomSheetState) {
        var wasVisible = false
        snapshotFlow { sheetState.bottomSheetState.currentValue }.collect { value ->
            if (value != SheetValue.Hidden) {
                wasVisible = true
            } else if (wasVisible) {
                wasVisible = false
                stopDetailViewModel.clearSelection()
            }
        }
    }

    // Stop Detail effects raised from inside the in-screen sheet (e.g. "Départ d'ici" -> Search).
    LaunchedEffect(stopDetailViewModel) {
        stopDetailViewModel.effects.collect { effect ->
            when (effect) {
                is StopDetailEffect.NavigateToSearch -> navController.navigate(effect.route)
                is StopDetailEffect.ShowOnMap -> Unit // Already on the map; the action is hidden here.
                is StopDetailEffect.OpenTimetable -> navController.navigate(
                    Screen.Timetable.createRoute(
                        stopId = effect.stopId,
                        lineNumber = effect.lineNumber,
                        directionId = effect.directionId,
                        stopName = effect.stopName,
                    ),
                )
                is StopDetailEffect.ShowMessage -> scope.launch {
                    val result = snackbarHostState.showSnackbar(effect.message, actionLabel = effect.actionLabel)
                    if (result == SnackbarResult.ActionPerformed) effect.action?.let(stopDetailViewModel::onEvent)
                }
            }
        }
    }

    // Enable the user-location dot once permission is granted and the style is ready; also uses
    // the location to recenter the default camera if the user is within the Nantes area.
    // MapLibre's LocationComponent doesn't expose a simple one-shot "first fix"
    // callback in this SDK surface, so this polls `lastKnownLocation` briefly after activation.
    LaunchedEffect(style, hasLocationPermission) {
        val map = mapLibreMap
        val currentStyle = style
        if (map == null || currentStyle == null || !hasLocationPermission || locationComponentActivated) {
            return@LaunchedEffect
        }
        val locationComponent = enableLocationComponent(map, currentStyle, context) ?: return@LaunchedEffect
        locationComponentActivated = true

        var attempts = 0
        while (attempts < LOCATION_POLL_ATTEMPTS && !hasCenteredOnUser) {
            val location = locationComponent.lastKnownLocation
            if (location != null) {
                viewModel.onEvent(MapEvent.UserLocationKnown(location.latitude, location.longitude))
                if (isWithinNantesArea(location.latitude, location.longitude)) {
                    hasCenteredOnUser = true
                    map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), DEFAULT_ZOOM),
                        USER_LOCATION_EASE_MS,
                    )
                }
                break
            }
            attempts++
            delay(LOCATION_POLL_DELAY_MS)
        }
    }

    // Back should dismiss the in-screen Stop Detail sheet (like closing the Stop Detail modal on the
    // other tabs) rather than leaving the Map / exiting the app. Enabled only while the sheet is up;
    // hiding it triggers the snapshotFlow effect above, which clears the selection and stops polling.
    BackHandler(enabled = sheetState.bottomSheetState.currentValue != SheetValue.Hidden) {
        scope.launch { sheetState.bottomSheetState.hide() }
    }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        sheetPeekHeight = STOP_SHEET_PEEK_HEIGHT,
        sheetContent = {
            // Rendered only while a stop is targeted; the sheet is Hidden otherwise. "Voir sur la
            // carte" is omitted here (showMapAction = false) - you are already on the map.
            if (stopDetailUiState.stopId.isNotBlank()) {
                // Fill the sheet's height so the departures list is the sheet's scroll driver: at the
                // peek it stays put, and only once expanded-and-scrolled-to-top does a further downward
                // swipe collapse the sheet (Material3 sheet <-> list nested scroll). The drag handle
                // always collapses it too.
                StopDetailSheetInner(
                    uiState = stopDetailUiState,
                    onEvent = stopDetailViewModel::onEvent,
                    showMapAction = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            OfflineAwareContent(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            MapSearchBar(
                query = uiState.searchQuery,
                isExpanded = uiState.isSearchExpanded,
                results = uiState.searchResults,
                isSearching = uiState.isSearching,
                showNoResults = uiState.showNoResults,
                onQueryChange = { viewModel.onEvent(MapEvent.SearchQueryChanged(it)) },
                onExpandedChange = { viewModel.onEvent(MapEvent.SearchExpandedChanged(it)) },
                onResultSelected = { stop ->
                    viewModel.onEvent(MapEvent.SearchExpandedChanged(false))
                    selectStopOnMap(stop)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(16.dp),
            )

            // "Recenter on me" - hidden while the sheet is up (the peek would cover it anyway).
            if (sheetState.bottomSheetState.currentValue == SheetValue.Hidden) {
                FloatingActionButton(
                    onClick = { recenterOnUser() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Recentrer sur ma position")
                }
            }
        }
    }
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun enableLocationComponent(map: MapLibreMap, style: Style, context: Context): LocationComponent? {
    if (!context.hasLocationPermission()) return null
    return try {
        val locationComponent = map.locationComponent
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).build(),
        )
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.NONE
        locationComponent.renderMode = RenderMode.COMPASS
        locationComponent
    } catch (_: SecurityException) {
        null
    }
}

/**
 * Creates a MapLibre [MapView] and forwards every relevant Android lifecycle event to it
 * (onCreate/onStart/onResume/onPause/onStop/onDestroy/onLowMemory), since `MapView` is a classic
 * Android `View` that needs these calls to manage its internal GL surface/tile cache correctly.
 *
 * `onSaveInstanceState` has no dedicated [Lifecycle.Event] (it's an `Activity`-callback concept,
 * not a lifecycle state) - this module cannot hook the real `Activity.onSaveInstanceState` without
 * touching `MainActivity`, which is out of scope here. As a best-effort proxy, `ON_PAUSE` (which
 * reliably fires before an owning `Activity`'s real `onSaveInstanceState` in practice) also calls
 * `mapView.onSaveInstanceState(Bundle())`. Camera position itself is intentionally not treated as
 * state worth restoring since the default-camera logic above is cheap to recompute. Flagged for
 * integration to revisit if precise state restoration (e.g. surviving process death) is required.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { id = View.generateViewId() }
    }

    // Captured once (the effect below is keyed only on `mapView`, which never changes identity
    // for the life of this composable) - stable for as long as this screen stays mounted, which
    // holds for a single-Activity Navigation-Compose destination.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mapView) {
        var destroyed = false
        fun destroyOnce() {
            if (!destroyed) {
                destroyed = true
                mapView.onDestroy()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onSaveInstanceState(Bundle())
                    mapView.onPause()
                }
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> destroyOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val componentCallbacks = object : ComponentCallbacks2 {
            override fun onLowMemory() {
                mapView.onLowMemory()
            }

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        context.applicationContext.registerComponentCallbacks(componentCallbacks)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(componentCallbacks)
            // Guarantees cleanup even when this composable is disposed (e.g. switching bottom-nav
            // tabs with `saveState = true`, which only stops - not destroys - the underlying
            // NavBackStackEntry lifecycle) without waiting for a real ON_DESTROY event.
            destroyOnce()
        }
    }
    return mapView
}

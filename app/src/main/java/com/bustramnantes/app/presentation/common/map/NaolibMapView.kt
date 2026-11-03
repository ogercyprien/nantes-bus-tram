package com.bustramnantes.app.presentation.common.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bustramnantes.app.presentation.map.MapStyleIds
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * A MapLibre map with the app's **base style** - the glyphs source (see [MapStyleIds.baseStyleJson]),
 * the beige backdrop and the OSM basemap - shared by the Plan tab ([com.bustramnantes.app.presentation.map.MapScreen])
 * and the itinerary maps. Each screen adds its own sources/layers on top in [onStyleLoaded], which
 * runs once per map instance with the map and its loaded style (keep them to update sources later).
 * The [MapView] is created once and gets every lifecycle event ([rememberMapViewWithLifecycle]).
 */
@Composable
fun NaolibMapView(
    onStyleLoaded: (MapLibreMap, Style) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val latestOnStyleLoaded by rememberUpdatedState(onStyleLoaded)
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(MapStyleIds.baseStyleJson())) { style ->
                // Beige backdrop first (bottom of the stack) so unloaded/offline areas look like a
                // loading map instead of a black void - see MapStyleIds.backgroundLayer.
                style.addLayer(MapStyleIds.backgroundLayer())
                style.addSource(MapStyleIds.osmRasterSource())
                style.addLayer(MapStyleIds.osmRasterLayer())
                latestOnStyleLoaded(map, style)
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
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
 * state worth restoring since the default-camera logic is cheap to recompute. Flagged for
 * integration to revisit if precise state restoration (e.g. surviving process death) is required.
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
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

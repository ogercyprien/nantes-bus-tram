package com.bustramnantes.app.presentation.itinerary.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.presentation.common.map.NaolibMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/**
 * One journey on a map (the journey detail view): each ride along its street path in its line
 * colour with a white casing and a line badge, walks dotted, served stops, transfer stops, A and B.
 * The camera fits the journey inside the part of the map left visible above [bottomInset] (the sheet
 * covering the bottom of the map). Tapping a stop calls [onStopClick] with its id.
 */
@Composable
fun JourneyMap(
    model: JourneyMapModel,
    bottomInset: Dp,
    onStopClick: (stopId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val latestOnStopClick by rememberUpdatedState(onStopClick)
    val density = LocalDensity.current

    NaolibMapView(
        onStyleLoaded = { m, s ->
            ItineraryMapLayers.addTo(s)
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapClickListener { latLng -> tapStop(m, latLng, density.density)?.let(latestOnStopClick) != null }
            map = m
            style = s
        },
        modifier = modifier,
    )

    LaunchedEffect(style, model) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(ItineraryMapLayers.SOURCE_LEGS)?.setGeoJson(FeatureCollection.fromFeatures(ItineraryMapLayers.legFeatures(model.legs)))
        s.getSourceAs<GeoJsonSource>(ItineraryMapLayers.SOURCE_STOPS)?.setGeoJson(ItineraryMapLayers.stopFeatures(model.stops))
        s.getSourceAs<GeoJsonSource>(ItineraryMapLayers.SOURCE_BADGES)?.setGeoJson(ItineraryMapLayers.badgeFeatures(model.legs))
        s.getSourceAs<GeoJsonSource>(ItineraryMapLayers.SOURCE_ENDPOINTS)?.setGeoJson(ItineraryMapLayers.endpointFeatures(model.origin, model.destination))
    }

    // Fit the journey on first show and when it changes (a real-time recheck may replace it).
    LaunchedEffect(map, style, model.origin, model.destination, bottomInset) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        fitCamera(m, model.allPoints, with(density) { bottomInset.toPx() }, with(density) { 40.dp.toPx() })
    }
}

/** The stop under a tap ([ItineraryMapLayers.LAYER_STOPS] / [ItineraryMapLayers.LAYER_TRANSFERS]), within a forgiving box. */
internal fun tapStop(map: MapLibreMap, latLng: LatLng, density: Float): String? {
    val p = map.projection.toScreenLocation(latLng)
    val r = 16 * density
    val box = android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r)
    return map.queryRenderedFeatures(box, ItineraryMapLayers.LAYER_TRANSFERS, ItineraryMapLayers.LAYER_STOPS)
        .firstNotNullOfOrNull { it.getStringProperty(ItineraryMapLayers.PROP_STOP_ID) }
}

/** Moves the camera to show [points], [marginPx] from the edges and above [bottomPx]. */
internal fun fitCamera(map: MapLibreMap, points: List<MapPoint>, bottomPx: Float, marginPx: Float, animate: Boolean = false) {
    if (points.isEmpty()) return
    val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
    val m = marginPx.toInt()
    val update = if (maxLat - minLat < 1e-5 && maxLon - minLon < 1e-5) {
        CameraUpdateFactory.newLatLngZoom(LatLng(minLat, minLon), 16.0)
    } else {
        val bounds = LatLngBounds.Builder().include(LatLng(minLat, minLon)).include(LatLng(maxLat, maxLon)).build()
        CameraUpdateFactory.newLatLngBounds(bounds, m, m, m, (bottomPx + m).toInt())
    }
    // Before the view is laid out the padding can exceed its size: skip, the next layout refits.
    runCatching { if (animate) map.easeCamera(update, 500) else map.moveCamera(update) }
}

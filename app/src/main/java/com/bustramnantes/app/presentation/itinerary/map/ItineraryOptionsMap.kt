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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import kotlin.math.roundToInt

/**
 * Every route of the results list on one map, each in its own colour ([OptionRoute.color], the bar on
 * its cards), side by side where they share a path ([OptionLanes]). With a [highlighted] route, it is
 * drawn wider and on top and the others fade. Tapping a route's line calls [onRouteTap] with its
 * index - the line closest to the finger, so each lane of a shared stretch can be picked - tapping
 * elsewhere with null.
 *
 * Lanes are laid out off the main thread for the current zoom, rounded to half a level (the offsets
 * are in map metres, so they are redone when the zoom moves on); the lane order is computed once per
 * set of routes and kept at every zoom. The camera fits every route above [bottomInset] when the
 * trip's ends change.
 */
@Composable
fun ItineraryOptionsMap(
    routes: List<OptionRoute>,
    highlighted: Int?,
    bottomInset: Dp,
    onRouteTap: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var zoomBucket by remember { mutableStateOf<Double?>(null) }
    val latestOnRouteTap by rememberUpdatedState(onRouteTap)
    val density = LocalDensity.current

    NaolibMapView(
        onStyleLoaded = { m, s ->
            ItineraryMapLayers.addTo(s, density.density)
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnCameraIdleListener { zoomBucket = (m.cameraPosition.zoom * 2).roundToInt() / 2.0 }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val r = TAP_TOLERANCE_DP * density.density
                val features = m.queryRenderedFeatures(
                    android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r),
                    ItineraryMapLayers.LAYER_RIDES, ItineraryMapLayers.LAYER_WALKS,
                )
                // Lanes side by side are a few dp apart, all inside the forgiving tap box: the route
                // whose line passes closest to the finger wins, not the first one MapLibre lists.
                val candidates = features.mapNotNull { f ->
                    val route = f.getNumberProperty(ItineraryMapLayers.PROP_ROUTE)?.toInt() ?: return@mapNotNull null
                    route to screenLines(m, f.geometry())
                }
                latestOnRouteTap(nearestRoute(p.x.toDouble(), p.y.toDouble(), candidates))
                true
            }
            map = m
            style = s
        },
        modifier = modifier,
    )

    // Camera: fit every route when the trip's ends change (a time shift or a real-time update keeps it).
    val origin = routes.firstOrNull()?.origin
    val destination = routes.firstOrNull()?.destination
    LaunchedEffect(map, style, origin, destination) {
        val m = map ?: return@LaunchedEffect
        if (style == null || routes.isEmpty()) return@LaunchedEffect
        fitCamera(m, routes.flatMap { r -> r.legs.flatMap { it.points } }, with(density) { bottomInset.toPx() }, with(density) { 40.dp.toPx() })
        zoomBucket = (m.cameraPosition.zoom * 2).roundToInt() / 2.0
    }

    // Lanes: order once per set of routes, offsets per zoom bucket.
    var rank by remember(routes) { mutableStateOf<IntArray?>(null) }
    var laidOut by remember(routes) { mutableStateOf<List<List<JourneyMapLeg>>?>(null) }
    LaunchedEffect(routes, zoomBucket) {
        val zoom = zoomBucket ?: return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            val lanes = OptionLanes(routes.map { r -> r.legs.map { it.toMercator() } }, OptionLanes.unitsPerDp(zoom))
            val order = rank ?: lanes.order()
            order to lanes.layout(order).mapIndexed { i, legs -> legs.mapIndexed { k, leg -> routes[i].legs[k].withMercator(leg) } }
        }
        rank = result.first
        laidOut = result.second
    }

    LaunchedEffect(style, laidOut, highlighted) {
        val s = style ?: return@LaunchedEffect
        val legsByRoute = laidOut ?: routes.map { it.legs }
        // The highlighted route last: drawn on top.
        val drawOrder = routes.indices.sortedBy { if (it == highlighted) 1 else 0 }
        val features = ArrayList<Feature>()
        for (r in drawOrder) {
            val emphasised = highlighted == null || highlighted == r
            features += ItineraryMapLayers.legFeatures(
                legsByRoute.getOrNull(r).orEmpty().filter { it.points.size >= 2 },
                width = if (highlighted == r) HIGHLIGHT_WIDTH_DP else LINE_WIDTH_DP,
                opacity = if (emphasised) 1f else FADED_OPACITY,
            ) { it.addNumberProperty(ItineraryMapLayers.PROP_ROUTE, r) }
        }
        s.getSourceAs<GeoJsonSource>(ItineraryMapLayers.SOURCE_LEGS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        val ends = routes.firstOrNull()
        s.getSourceAs<GeoJsonSource>(ItineraryMapLayers.SOURCE_ENDPOINTS)?.setGeoJson(
            if (ends == null) FeatureCollection.fromFeatures(emptyList()) else ItineraryMapLayers.endpointFeatures(ends.origin, ends.destination),
        )
    }
}

private const val LINE_WIDTH_DP = 4.5f
private const val HIGHLIGHT_WIDTH_DP = 5.5f
private const val FADED_OPACITY = 0.4f
private const val TAP_TOLERANCE_DP = 14f

/** A rendered line feature's geometry in screen pixels (one list per line part). */
private fun screenLines(map: MapLibreMap, geometry: Geometry?): List<List<DoubleArray>> {
    val parts = when (geometry) {
        is LineString -> listOf(geometry.coordinates())
        is MultiLineString -> geometry.coordinates()
        else -> emptyList()
    }
    return parts.map { part ->
        part.map { pt -> map.projection.toScreenLocation(LatLng(pt.latitude(), pt.longitude())).let { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) } }
    }
}

private fun JourneyMapLeg.toMercator(): OptionLanes.Leg {
    val xy = points.map { OptionLanes.toMercator(it.lat, it.lon) }
    return OptionLanes.Leg(DoubleArray(xy.size) { xy[it].first }, DoubleArray(xy.size) { xy[it].second }, isWalk)
}

private fun JourneyMapLeg.withMercator(leg: OptionLanes.Leg): JourneyMapLeg =
    copy(points = leg.x.indices.map { OptionLanes.fromMercator(leg.x[it], leg.y[it]) })

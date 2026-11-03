package com.bustramnantes.app.presentation.itinerary.map

import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Sources, layers and GeoJSON for the itinerary maps (on top of the shared base style of
 * [com.bustramnantes.app.presentation.common.map.NaolibMapView]). Bottom to top: walks (dotted),
 * ride casings (white) and rides (line colour), served stops, transfer stops, line badges, A/B.
 */
object ItineraryMapLayers {
    const val SOURCE_LEGS = "itinerary-legs-source"
    const val SOURCE_STOPS = "itinerary-stops-source"
    const val SOURCE_BADGES = "itinerary-badges-source"
    const val SOURCE_ENDPOINTS = "itinerary-endpoints-source"

    const val LAYER_WALKS = "itinerary-walks-layer"
    const val LAYER_RIDE_CASINGS = "itinerary-ride-casings-layer"
    const val LAYER_RIDES = "itinerary-rides-layer"
    const val LAYER_STOPS = "itinerary-stops-layer"
    const val LAYER_TRANSFERS = "itinerary-transfers-layer"
    const val LAYER_BADGES = "itinerary-badges-layer"
    const val LAYER_ENDPOINTS = "itinerary-endpoints-layer"
    const val LAYER_ENDPOINT_LABELS = "itinerary-endpoint-labels-layer"

    const val PROP_KIND = "kind"
    const val PROP_COLOR = "color"
    const val PROP_TEXT_COLOR = "textColor"
    const val PROP_LABEL = "label"
    const val PROP_STOP_ID = "stopId"
    const val PROP_WIDTH = "width"
    const val PROP_OPACITY = "opacity"
    /** Index of the [OptionRoute] a results-map line belongs to (for taps). */
    const val PROP_ROUTE = "route"

    private const val KIND_RIDE = "ride"
    private const val KIND_WALK = "walk"
    private const val KIND_TRANSFER = "transfer"
    private const val KIND_STOP = "stop"
    private const val ORIGIN_COLOR = "#212121"
    private const val DESTINATION_COLOR = "#C62828"
    private val FONT = arrayOf("Open Sans Bold", "Arial Unicode MS Bold")

    /** Adds every itinerary source (empty) and layer to [style]. */
    fun addTo(style: org.maplibre.android.maps.Style) {
        for (id in listOf(SOURCE_LEGS, SOURCE_STOPS, SOURCE_BADGES, SOURCE_ENDPOINTS)) {
            style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())))
        }
        style.addLayer(
            LineLayer(LAYER_WALKS, SOURCE_LEGS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_WALK), literal(true), literal(false)))
                setProperties(
                    lineColor(toColor(get(PROP_COLOR))),
                    lineWidth(get(PROP_WIDTH)),
                    lineOpacity(get(PROP_OPACITY)),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                    // Round caps on near-zero dashes = dots.
                    lineDasharray(arrayOf(0.01f, 1.8f)),
                )
            },
        )
        style.addLayer(
            LineLayer(LAYER_RIDE_CASINGS, SOURCE_LEGS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_RIDE), literal(true), literal(false)))
                setProperties(
                    lineColor(toColor(literal("#FFFFFF"))),
                    lineWidth(org.maplibre.android.style.expressions.Expression.sum(get(PROP_WIDTH), literal(2.5f))),
                    lineOpacity(get(PROP_OPACITY)),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                )
            },
        )
        style.addLayer(
            LineLayer(LAYER_RIDES, SOURCE_LEGS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_RIDE), literal(true), literal(false)))
                setProperties(
                    lineColor(toColor(get(PROP_COLOR))),
                    lineWidth(get(PROP_WIDTH)),
                    lineOpacity(get(PROP_OPACITY)),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                )
            },
        )
        style.addLayer(
            CircleLayer(LAYER_STOPS, SOURCE_STOPS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_STOP), literal(true), literal(false)))
                setProperties(
                    circleRadius(3.5f),
                    circleColor(toColor(literal("#FFFFFF"))),
                    circleStrokeColor(toColor(get(PROP_COLOR))),
                    circleStrokeWidth(1.5f),
                )
            },
        )
        style.addLayer(
            CircleLayer(LAYER_TRANSFERS, SOURCE_STOPS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_TRANSFER), literal(true), literal(false)))
                setProperties(
                    circleRadius(6f),
                    circleColor(toColor(literal("#FFFFFF"))),
                    circleStrokeColor(toColor(literal("#212121"))),
                    circleStrokeWidth(2.5f),
                )
            },
        )
        style.addLayer(
            SymbolLayer(LAYER_BADGES, SOURCE_BADGES).apply {
                setProperties(
                    textField(get(PROP_LABEL)),
                    textFont(FONT),
                    textSize(13f),
                    textColor(toColor(get(PROP_TEXT_COLOR))),
                    // A wide halo in the line colour reads as the line's badge.
                    textHaloColor(toColor(get(PROP_COLOR))),
                    textHaloWidth(4f),
                    textAllowOverlap(false),
                )
            },
        )
        style.addLayer(
            CircleLayer(LAYER_ENDPOINTS, SOURCE_ENDPOINTS).apply {
                setProperties(
                    circleRadius(10f),
                    circleColor(toColor(get(PROP_COLOR))),
                    circleStrokeColor(toColor(literal("#FFFFFF"))),
                    circleStrokeWidth(2.5f),
                )
            },
        )
        style.addLayer(
            SymbolLayer(LAYER_ENDPOINT_LABELS, SOURCE_ENDPOINTS).apply {
                setProperties(
                    textField(get(PROP_LABEL)),
                    textFont(FONT),
                    textSize(12f),
                    textColor(toColor(literal("#FFFFFF"))),
                    textAllowOverlap(true),
                    textIgnorePlacement(true),
                )
            },
        )
    }

    /** One line feature per leg, drawn [width] px wide at [opacity]. */
    fun legFeatures(legs: List<JourneyMapLeg>, width: Float = 5f, opacity: Float = 1f, extra: (Feature) -> Unit = {}): List<Feature> =
        legs.map { leg ->
            Feature.fromGeometry(LineString.fromLngLats(leg.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty(PROP_KIND, if (leg.isWalk) KIND_WALK else KIND_RIDE)
                addStringProperty(PROP_COLOR, leg.color)
                addNumberProperty(PROP_WIDTH, if (leg.isWalk) width * 0.7f else width)
                addNumberProperty(PROP_OPACITY, opacity)
                extra(this)
            }
        }

    fun stopFeatures(stops: List<JourneyMapStop>): FeatureCollection = FeatureCollection.fromFeatures(
        stops.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.point.lon, s.point.lat)).apply {
                addStringProperty(PROP_KIND, if (s.isTransfer) KIND_TRANSFER else KIND_STOP)
                addStringProperty(PROP_COLOR, s.color)
                addStringProperty(PROP_STOP_ID, s.stopId)
            }
        },
    )

    /** A line-number badge at the middle of each ride. */
    fun badgeFeatures(legs: List<JourneyMapLeg>): FeatureCollection = FeatureCollection.fromFeatures(
        legs.filter { !it.isWalk && it.lineNumber != null }.map { leg ->
            val mid = midpoint(leg.points)
            Feature.fromGeometry(Point.fromLngLat(mid.lon, mid.lat)).apply {
                addStringProperty(PROP_LABEL, " ${leg.lineNumber} ")
                addStringProperty(PROP_COLOR, leg.color)
                addStringProperty(PROP_TEXT_COLOR, leg.textColor)
            }
        },
    )

    fun endpointFeatures(origin: MapPoint, destination: MapPoint): FeatureCollection = FeatureCollection.fromFeatures(
        listOf(
            Feature.fromGeometry(Point.fromLngLat(origin.lon, origin.lat)).apply {
                addStringProperty(PROP_LABEL, "A"); addStringProperty(PROP_COLOR, ORIGIN_COLOR)
            },
            Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)).apply {
                addStringProperty(PROP_LABEL, "B"); addStringProperty(PROP_COLOR, DESTINATION_COLOR)
            },
        ),
    )

    /** The point halfway along [points] (by length, flat approximation - fine at city scale). */
    internal fun midpoint(points: List<MapPoint>): MapPoint {
        if (points.size < 2) return points.first()
        val k = kotlin.math.cos(Math.toRadians(points[0].lat))
        val seg = (0 until points.size - 1).map { i ->
            kotlin.math.hypot((points[i + 1].lon - points[i].lon) * k, points[i + 1].lat - points[i].lat)
        }
        var remaining = seg.sum() / 2
        for (i in seg.indices) {
            if (remaining <= seg[i] && seg[i] > 0) {
                val t = remaining / seg[i]
                return MapPoint(points[i].lat + (points[i + 1].lat - points[i].lat) * t, points[i].lon + (points[i + 1].lon - points[i].lon) * t)
            }
            remaining -= seg[i]
        }
        return points.last()
    }
}

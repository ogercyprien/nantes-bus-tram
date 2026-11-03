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
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
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
 * ride casings (white) and rides (line colour), served stops, transfer stops, line badges, then the
 * start (green dot) and the arrival (checkered dot).
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
    const val LAYER_START = "itinerary-start-layer"
    const val LAYER_FINISH = "itinerary-finish-layer"
    private const val IMAGE_FINISH = "itinerary-finish-image"

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
    private const val KIND_START = "start"
    private const val KIND_FINISH = "finish"
    private const val START_COLOR = "#2E9E44"
    /** Start dot radius + white ring (dp); the finish dot is drawn the same size. */
    private const val END_DOT_RADIUS_DP = 8f
    private const val END_DOT_RING_DP = 3f
    private val FONT = arrayOf("Open Sans Bold", "Arial Unicode MS Bold")

    /** Adds every itinerary source (empty), image and layer to [style]; [density] = screen density. */
    fun addTo(style: org.maplibre.android.maps.Style, density: Float) {
        style.addImage(IMAGE_FINISH, finishDot(density))
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
            CircleLayer(LAYER_START, SOURCE_ENDPOINTS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_START), literal(true), literal(false)))
                setProperties(
                    circleRadius(END_DOT_RADIUS_DP),
                    circleColor(toColor(literal(START_COLOR))),
                    circleStrokeColor(toColor(literal("#FFFFFF"))),
                    circleStrokeWidth(END_DOT_RING_DP),
                )
            },
        )
        style.addLayer(
            SymbolLayer(LAYER_FINISH, SOURCE_ENDPOINTS).apply {
                setFilter(match(get(PROP_KIND), literal(KIND_FINISH), literal(true), literal(false)))
                setProperties(
                    iconImage(IMAGE_FINISH),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                )
            },
        )
    }

    /**
     * The arrival marker: a checkered (finish-flag) disc in a white ring, the size of the start dot.
     * A circle layer can't carry a pattern, hence an icon drawn here.
     */
    private fun finishDot(density: Float): android.graphics.Bitmap {
        val outer = (END_DOT_RADIUS_DP + END_DOT_RING_DP) * density
        val size = kotlin.math.ceil(outer * 2).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val c = size / 2f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(c, c, outer, paint)
        val inner = END_DOT_RADIUS_DP * density
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addCircle(c, c, inner, android.graphics.Path.Direction.CW) })
        val cells = 4
        val cell = 2 * inner / cells
        for (i in 0 until cells) for (j in 0 until cells) {
            paint.color = if ((i + j) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            canvas.drawRect(c - inner + i * cell, c - inner + j * cell, c - inner + (i + 1) * cell, c - inner + (j + 1) * cell, paint)
        }
        canvas.restore()
        // A hairline so the white squares don't melt into the white ring.
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = android.graphics.Color.parseColor("#424242")
        canvas.drawCircle(c, c, inner, paint)
        return bitmap
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
            Feature.fromGeometry(Point.fromLngLat(origin.lon, origin.lat)).apply { addStringProperty(PROP_KIND, KIND_START) },
            Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)).apply { addStringProperty(PROP_KIND, KIND_FINISH) },
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

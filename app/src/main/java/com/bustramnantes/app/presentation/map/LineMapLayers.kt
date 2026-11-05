package com.bustramnantes.app.presentation.map

import com.bustramnantes.transit.lines.LaneGeometry
import com.bustramnantes.transit.lines.LineMap
import com.bustramnantes.transit.lines.LineStyle
import com.bustramnantes.transit.lines.MapRoute
import com.bustramnantes.transit.lines.WebMercator
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
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
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
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
import kotlin.math.pow

/**
 * The Plan tab's lines, drawn from our own line map (`:transit` `lines/`, whose README explains every
 * rule) instead of the official raster tiles.
 *
 * **Geometry per half zoom, scaled in between.** [render] turns a layout's [LaneGeometry] for one draw
 * zoom into GeoJSON (lanes already offset, junction curves, station dashes, stop dots). The screen
 * calls it again when the zoom moves to another half step (off the main thread, like the itinerary
 * results map's lanes). In between, every width is scaled with the map, `w * 2^(zoom - z0)`: features
 * carry their width at zoom 0 (`w0`) and the layers interpolate exponentially (base 2) from it, so the
 * picture behaves like a raster tile zoomed a little and lanes never drift apart from their offsets.
 *
 * **Layers, bottom to top**: a white casing under every line; buses; boats (dashed); Busway and
 * Chronobus, **one layer per line** (a Chronobus is its `#6B2359` edge, its colour, then its white
 * stripe, 12 % of the width each, and the next line is drawn whole above it, so a line crossing a
 * Chronobus hides its stripe); trams on top. Line numbers along the lanes from z13, then stop dots
 * and station dashes (from z14) over the lines. Taps on them carry the GTFS StopPlace id ([PROP_STOP]).
 *
 * **Labels**: one line number per lane piece, placed at its middle (`line-center`: `line` placement
 * only labels a feature longer than the label spacing), collisions thinned by MapLibre so a bundle
 * shows a readable scatter; in the line's colour with a white halo, so it reads on its own lane or
 * on the map beside it. Font: the bundled Noto Sans ([MapStyleIds.LABEL_FONT]).
 */
object LineMapLayers {
    const val SOURCE_LINES = "linemap-lines-source"
    const val SOURCE_MARKERS = "linemap-markers-source"
    private const val LAYER_CASING = "linemap-casing"
    private const val LAYER_BUS = "linemap-bus"
    private const val LAYER_BOAT = "linemap-boat"
    private const val LAYER_TRAM = "linemap-tram"
    private const val LAYER_LABELS = "linemap-labels"
    const val LAYER_DASH_OUTLINE = "linemap-dash-outline"
    const val LAYER_DASH = "linemap-dash"
    const val LAYER_DOTS = "linemap-dots"
    private const val MAJOR_PREFIX = "linemap-major-"

    const val PROP_STOP = "stopId"
    private const val PROP_ROUTE = "r"
    private const val PROP_KIND = "k"
    private const val PROP_COLOR = "c"
    private const val PROP_W0 = "w0"
    private const val PROP_CASING0 = "cw0"
    const val PROP_LABEL = "n"
    private const val PROP_OUTLINE0 = "o0"

    /** Line numbers from this zoom (below, the network is too dense for them to help). */
    private const val LABEL_MIN_ZOOM = 13f

    private const val CHRONOBUS_EDGE = "#6B2359"
    private const val MARKER_BLUE = "#1565C0"

    /** GeoJSON for one draw zoom; [markerStops] = StopPlace ids with a marker on the lines. */
    class Rendered(val lines: FeatureCollection, val markers: FeatureCollection, val markerStops: Set<String>)

    /** Lanes, curves, dashes and dots of [map] at draw zoom [zoom] (pure; run off the main thread). */
    fun render(map: LineMap, zoom: Double): Rendered {
        val geometry = LaneGeometry.compute(map, zoom)
        val style = map.style
        val toZero = 2.0.pow(-zoom)
        val gap = style.gap(zoom)
        val lines = ArrayList<Feature>(geometry.pieces.size)
        // Grouped by line, in rank then route order: one line's pieces are consecutive in its layer.
        for (p in geometry.pieces.sortedWith(compareBy({ style.rank(it.route) }, { it.route }))) {
            val route = map.routes[p.route]
            val w = style.width(p.route, zoom)
            lines += Feature.fromGeometry(lineString(p.x, p.y)).apply {
                addNumberProperty(PROP_ROUTE, p.route)
                addStringProperty(PROP_KIND, kindName(route.kind))
                addStringProperty(PROP_COLOR, "#" + route.color.ifBlank { "888888" })
                addNumberProperty(PROP_W0, w * toZero)
                addNumberProperty(PROP_CASING0, (w + gap) * toZero)
                if (!p.connector) addStringProperty(PROP_LABEL, route.shortName)
            }
        }
        val markers = ArrayList<Feature>()
        val stroke = LineStyle.interp(zoom, LaneGeometry.MARKER_STROKE)
        val dashed = HashSet<String>()
        for (d in geometry.dashes) {
            dashed += d.stopId
            markers += Feature.fromGeometry(lineString(doubleArrayOf(d.x0, d.x1), doubleArrayOf(d.y0, d.y1))).apply {
                addStringProperty(PROP_STOP, d.stopId)
                addNumberProperty(PROP_W0, d.thicknessDp * toZero)
                addNumberProperty(PROP_OUTLINE0, (d.thicknessDp + 2 * stroke) * toZero)
            }
        }
        for (m in map.stops) {
            if (m.stopId in dashed) continue
            markers += Feature.fromGeometry(Point.fromLngLat(WebMercator.lon(m.x), WebMercator.lat(m.y))).apply {
                addStringProperty(PROP_STOP, m.stopId)
            }
        }
        return Rendered(FeatureCollection.fromFeatures(lines), FeatureCollection.fromFeatures(markers), map.stops.mapTo(HashSet()) { it.stopId })
    }

    /**
     * Adds the sources and layers for [routes] below [belowLayer] (the selected-stop ring and the
     * fallback stop dots stay on top), replacing any from a previous call: the per-line layers depend
     * on the network's Busway/Chronobus lines.
     */
    fun install(style: Style, routes: List<MapRoute>, belowLayer: String) {
        remove(style)
        style.addSource(GeoJsonSource(SOURCE_LINES))
        style.addSource(GeoJsonSource(SOURCE_MARKERS))
        val layers = ArrayList<Layer>()
        layers += line(LAYER_CASING, PROP_CASING0).withProperties(lineColor("#FFFFFF"))
        layers += line(LAYER_BUS, PROP_W0).withProperties(lineColor(toColor(get(PROP_COLOR))))
            .also { it.setFilter(eq(get(PROP_KIND), literal("bus"))) }
        layers += line(LAYER_BOAT, PROP_W0, round = false).withProperties(lineColor(toColor(get(PROP_COLOR))), lineDasharray(arrayOf(1.5f, 1f)))
            .also { it.setFilter(eq(get(PROP_KIND), literal("boat"))) }
        val chronobus = LineStyle(routes).chronobus
        for ((r, route) in routes.withIndex()) {
            if (route.kind != LineStyle.Kind.MAJOR) continue
            val only = eq(get(PROP_ROUTE), literal(r))
            if (chronobus[r]) {
                layers += line("$MAJOR_PREFIX$r-edge", PROP_W0).withProperties(lineColor(CHRONOBUS_EDGE)).also { it.setFilter(only) }
                layers += line("$MAJOR_PREFIX$r-body", PROP_W0, 1 - 2 * LineStyle.CHRONOBUS_EDGE_SHARE)
                    .withProperties(lineColor(toColor(get(PROP_COLOR)))).also { it.setFilter(only) }
                layers += line("$MAJOR_PREFIX$r-stripe", PROP_W0, LineStyle.CHRONOBUS_STRIPE_SHARE)
                    .withProperties(lineColor("#FFFFFF")).also { it.setFilter(only) }
            } else {
                layers += line("$MAJOR_PREFIX$r-body", PROP_W0).withProperties(lineColor(toColor(get(PROP_COLOR)))).also { it.setFilter(only) }
            }
        }
        layers += line(LAYER_TRAM, PROP_W0).withProperties(lineColor(toColor(get(PROP_COLOR))))
            .also { it.setFilter(eq(get(PROP_KIND), literal("tram"))) }
        layers += SymbolLayer(LAYER_LABELS, SOURCE_LINES).withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
            textField(get(PROP_LABEL)),
            textColor(toColor(get(PROP_COLOR))),
            textHaloColor("#FFFFFF"),
            textHaloWidth(1.6f),
            textSize(13f),
            textAllowOverlap(false),
            textFont(MapStyleIds.LABEL_FONT),
        ).also {
            it.minZoom = LABEL_MIN_ZOOM
            it.setFilter(Expression.has(PROP_LABEL))
        }
        layers += LineLayer(LAYER_DASH_OUTLINE, SOURCE_MARKERS).withProperties(
            lineWidth(scaled(PROP_OUTLINE0)), lineColor(MARKER_BLUE), lineCap(Property.LINE_CAP_ROUND),
        )
        layers += LineLayer(LAYER_DASH, SOURCE_MARKERS).withProperties(
            lineWidth(scaled(PROP_W0)), lineColor("#FFFFFF"), lineCap(Property.LINE_CAP_ROUND),
        )
        layers += CircleLayer(LAYER_DOTS, SOURCE_MARKERS).withProperties(
            circleRadius(byZoom(LaneGeometry.MARKER_RADIUS)),
            circleColor("#FFFFFF"),
            circleStrokeColor(MARKER_BLUE),
            circleStrokeWidth(byZoom(LaneGeometry.MARKER_STROKE)),
        )
        for (layer in layers) style.addLayerBelow(layer, belowLayer)
    }

    /** Removes every layer and source [install] added (switching back to the official tiles). */
    fun remove(style: Style) {
        for (layer in style.layers.toList()) if (layer.id.startsWith("linemap-")) style.removeLayer(layer)
        style.removeSource(SOURCE_LINES)
        style.removeSource(SOURCE_MARKERS)
    }

    /** Puts [rendered] on screen (main thread). */
    fun show(style: Style, rendered: Rendered) {
        style.getSourceAs<GeoJsonSource>(SOURCE_LINES)?.setGeoJson(rendered.lines)
        style.getSourceAs<GeoJsonSource>(SOURCE_MARKERS)?.setGeoJson(rendered.markers)
    }

    private fun line(id: String, widthProp: String, share: Double = 1.0, round: Boolean = true): LineLayer =
        LineLayer(id, SOURCE_LINES).withProperties(
            lineWidth(scaled(widthProp, share)),
            lineCap(if (round) Property.LINE_CAP_ROUND else Property.LINE_CAP_BUTT),
            lineJoin(Property.LINE_JOIN_ROUND),
        )

    /**
     * A feature's width at zoom 0 ([prop], times [share]) scaled with the map: `w0 * 2^zoom`, as an
     * exponential interpolation (MapLibre allows `zoom` only as the input of a top-level interpolate).
     */
    private fun scaled(prop: String, share: Double = 1.0): Expression = interpolate(
        exponential(2f), zoom(),
        stop(0, product(get(prop), literal(share))),
        stop(24, product(get(prop), literal(share * 2.0.pow(24)))),
    )

    private fun byZoom(stops: List<Pair<Double, Double>>): Expression =
        interpolate(linear(), zoom(), *stops.map { (z, v) -> stop(z, literal(v)) }.toTypedArray())

    private fun kindName(kind: LineStyle.Kind) = when (kind) {
        LineStyle.Kind.TRAM -> "tram"
        LineStyle.Kind.MAJOR -> "major"
        LineStyle.Kind.BOAT -> "boat"
        else -> "bus"
    }

    private fun lineString(x: DoubleArray, y: DoubleArray): LineString =
        LineString.fromLngLats(x.indices.map { Point.fromLngLat(WebMercator.lon(x[it]), WebMercator.lat(y[it])) })
}

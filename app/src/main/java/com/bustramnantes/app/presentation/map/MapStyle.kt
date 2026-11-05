package com.bustramnantes.app.presentation.map

import com.bustramnantes.app.domain.model.Stop
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Source/layer ids and remote URLs for the Map screen's style. Centralized
 * here so [MapScreen]'s click handling (`queryRenderedFeatures`) and style construction agree on
 * layer ids without magic-string duplication.
 */
object MapStyleIds {
    const val LAYER_BACKGROUND = "map-background-layer"

    /**
     * Paper-beige backdrop drawn beneath everything, so before/while the OSM basemap tiles load (or
     * offline, when they never do) the map reads as "a map that is loading" rather than a black void
     * that looks like a rendering bug. Matches the warm off-white of typical printed transit maps.
     */
    private const val MAP_BACKGROUND_COLOR = "#EFE9DD"

    const val SOURCE_OSM_RASTER = "osm-raster-source"
    const val LAYER_OSM_RASTER = "osm-raster-layer"
    private const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

    /**
     * The visible colored line traces are drawn as a **pre-rendered raster overlay**, exactly like
     * the official Naolib web map. The official app draws the network with two separate things
     * (confirmed by disassembling `plan-app.naolib.fr`'s JS bundle):
     * - a `raster` layer from `/data/raster/v5/{z}/{x}/{y}.png` for the *visible* lines, and
     * - an (effectively invisible) `clickable-network` vector layer only for hit-testing taps.
     *
     * Crucially, the vector network tiles store each corridor as byte-identical, un-offset 2-vertex
     * segments where routes share a street - so there is **no way** to render clean parallel lanes
     * from them with MapLibre `line-offset` (it is computed per-segment and produces gaps/kinks, and
     * co-located routes just stack on identical geometry). The parallel-lane separation is instead
     * baked into these raster tiles by the upstream cartography pipeline. We use the same tiles so the
     * traces look identical to the official map with zero client-side geometry work.
     */
    const val SOURCE_NETWORK_RASTER = "naolib-network-raster-source"
    const val LAYER_NETWORK_RASTER = "naolib-network-raster-layer"

    /**
     * Day-theme raster line tiles (the app uses a light basemap, so always day/`v5`). Standard
     * slippy XYZ scheme; 512-px tiles → [RASTER_TILE_SIZE]. Available z6-z17 (probed live); MapLibre
     * over-zooms past z17. Night variant is `/data/raster/v1nuit/{z}/{x}/{y}.png` if ever needed.
     */
    private const val NETWORK_RASTER_TILE_URL = "https://plan-app.naolib.fr/data/raster/v5/{z}/{x}/{y}.png"
    private const val NETWORK_RASTER_MIN_ZOOM = 6f
    private const val NETWORK_RASTER_MAX_ZOOM = 17f
    private const val RASTER_TILE_SIZE = 512

    const val SOURCE_NETWORK = "naolib-network-source"

    /**
     * Source-layer id inside the vector network tiles (`tile.cartographie.pro/data/naolib_network`),
     * confirmed against the live TileJSON. Kept solely to place line-number labels along routes via
     * [networkLabelLayer]; the *visible* colored lines come from the raster overlay above.
     */
    const val NETWORK_SOURCE_LAYER = "network"
    const val LAYER_NETWORK_LABELS = "naolib-network-labels-layer"
    const val NETWORK_TILEJSON_URL = "https://plan-app.naolib.fr/api/external/tilejson/naolib_network.json"

    /**
     * MapLibre needs a **glyphs** (font-PBF) source to render ANY `SymbolLayer` text. A style built
     * from a bare `Style.Builder()` has none, so symbol text silently never draws (raster/circle
     * layers are unaffected - they need no fonts). We therefore start the style from a minimal JSON
     * that sets only `glyphs`, then add every source/layer programmatically on top (see
     * [baseStyleJson] / its use in `MapScreen`).
     *
     * The glyphs are **bundled** (`assets/glyphs/`, Noto Sans Bold, SIL OFL, Latin ranges only): map
     * text works offline from the first launch and needs no font server. [LABEL_FONT] is the only
     * font stack; its directory name has no spaces because MapLibre puts it in the asset path.
     */
    private const val GLYPHS_URL = "asset://glyphs/{fontstack}/{range}.pbf"

    /** The one bundled font stack (see [GLYPHS_URL]), for every map label. */
    val LABEL_FONT = arrayOf("NotoSans-Bold")

    /** Minimal base style whose only job is to declare [GLYPHS_URL]; sources/layers are added after. */
    fun baseStyleJson(): String =
        """{"version":8,"glyphs":"$GLYPHS_URL","sources":{},"layers":[]}"""

    const val SOURCE_STOPS = "stops-source"
    const val LAYER_STOPS = "stops-layer"

    const val SOURCE_SELECTED_STOP = "selected-stop-source"
    const val LAYER_SELECTED_STOP = "selected-stop-layer"

    /** GeoJSON feature property key used to round-trip [Stop.id] through `queryRenderedFeatures`. */
    const val STOP_PROPERTY_ID = "stopId"
    const val STOP_PROPERTY_NAME = "name"

    fun osmRasterSource(): RasterSource {
        val tileSet = TileSet("2.1.0", OSM_TILE_URL).apply {
            attribution = OSM_ATTRIBUTION
            minZoom = 0f
            maxZoom = 19f
        }
        return RasterSource(SOURCE_OSM_RASTER, tileSet, 256)
    }

    /** Solid beige backdrop (see [MAP_BACKGROUND_COLOR]); add first so it sits under all tiles. */
    fun backgroundLayer(): BackgroundLayer =
        BackgroundLayer(LAYER_BACKGROUND).apply {
            setProperties(backgroundColor(android.graphics.Color.parseColor(MAP_BACKGROUND_COLOR)))
        }

    fun osmRasterLayer(): RasterLayer =
        RasterLayer(LAYER_OSM_RASTER, SOURCE_OSM_RASTER).apply { setProperties(rasterOpacity(1f)) }

    /**
     * Raster source for the visible line traces (pre-offset, non-overlapping artwork baked upstream -
     * see [SOURCE_NETWORK_RASTER]). This is what makes co-located routes render as clean parallel
     * ribbons instead of overlapping, matching the official Naolib map exactly.
     */
    fun networkRasterSource(): RasterSource {
        val tileSet = TileSet("2.1.0", NETWORK_RASTER_TILE_URL).apply {
            minZoom = NETWORK_RASTER_MIN_ZOOM
            maxZoom = NETWORK_RASTER_MAX_ZOOM
        }
        return RasterSource(SOURCE_NETWORK_RASTER, tileSet, RASTER_TILE_SIZE)
    }

    fun networkRasterLayer(): RasterLayer =
        RasterLayer(LAYER_NETWORK_RASTER, SOURCE_NETWORK_RASTER).apply { setProperties(rasterOpacity(1f)) }

    /**
     * Vector source for the network tiles, kept only to drive [networkLabelLayer] (line-number labels
     * along routes). The visible colored lines come from [networkRasterLayer]; this vector data still
     * carries the un-offset centerline geometry plus per-route properties (`route_short_name`,
     * `route_text_color`) needed for labels. `route_color`/`route_text_color` already carry a leading
     * `#`, so they feed straight into `to-color` (prepending another `#` yields `"##…"`, unparseable).
     */
    fun networkVectorSource(): VectorSource = VectorSource(SOURCE_NETWORK, NETWORK_TILEJSON_URL)

    /**
     * Line-number labels placed along each route, visible from zoom 13 up. The number is drawn in the
     * line's own [route_color] with a white halo, so it stays legible whether it lands on a colored
     * ribbon or in a gap between the parallel ribbons of the raster overlay (drawing it in
     * `route_text_color` - typically white - would vanish over the light map background).
     *
     * **Placement = `line-center`, not `line`.** The vector network is stored as many tiny 2-vertex
     * segments (~10-20 px on screen). `symbol-placement: line` only places a label where a single
     * feature is longer than the label spacing, which these segments never are - so it placed *zero*
     * labels (the reason none ever showed). `line-center` puts exactly one label at the midpoint of
     * each segment regardless of length; `text-allow-overlap = false` then collision-thins the
     * duplicates on shared corridors down to a readable scatter.
     */
    fun networkLabelLayer(): SymbolLayer =
        SymbolLayer(LAYER_NETWORK_LABELS, SOURCE_NETWORK).apply {
            sourceLayer = NETWORK_SOURCE_LAYER
            minZoom = 13f
            // Some segments carry route_short_name "NA" (unnamed service variants); don't label them.
            setFilter(neq(get("route_short_name"), literal("NA")))
            setProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                textField(get("route_short_name")),
                // route_color already carries a leading '#' - see networkVectorSource's note.
                textColor(toColor(get("route_color"))),
                textHaloColor(android.graphics.Color.WHITE),
                textHaloWidth(1.6f),
                textSize(13f),
                textAllowOverlap(false),
                textFont(LABEL_FONT),
            )
        }

    fun stopsGeoJsonSource(stops: List<Stop>): GeoJsonSource =
        GeoJsonSource(SOURCE_STOPS, stopsToFeatureCollection(stops))

    /**
     * Circle markers sized by zoom (a circle layer sized by zoom level). Radii were
     * enlarged from the original 2/4/9px so the dots are easier to tap - the click handler in
     * [MapScreen] additionally queries a padded box around the tap point for extra tolerance.
     */
    fun stopsCircleLayer(): CircleLayer =
        CircleLayer(LAYER_STOPS, SOURCE_STOPS).apply {
            setProperties(
                circleRadius(
                    interpolate(
                        linear(),
                        zoom(),
                        stop(11, literal(4.0f)),
                        stop(14, literal(7.0f)),
                        stop(18, literal(12.0f)),
                    ),
                ),
                circleColor(color(android.graphics.Color.WHITE)),
                circleStrokeColor(color(android.graphics.Color.parseColor("#1565C0"))),
                circleStrokeWidth(
                    interpolate(
                        linear(),
                        zoom(),
                        stop(11, literal(1.5f)),
                        stop(18, literal(3.0f)),
                    ),
                ),
            )
        }

    /** Highlight color for the last-selected stop's ring (a warm accent, distinct from the stop stroke). */
    private const val SELECTED_STOP_COLOR = "#FF6D00"

    /**
     * A single-feature source holding the stop the user last opened, so it can stay visually marked
     * on the map after the Stop Detail sheet is dismissed. Empty when nothing is selected.
     */
    fun selectedStopSource(stop: Stop?): GeoJsonSource =
        GeoJsonSource(SOURCE_SELECTED_STOP, stopsToFeatureCollection(listOfNotNull(stop)))

    /**
     * A translucent accent halo (with a solid ring edge) drawn *under* the white stop dot to mark the
     * last-selected stop. Sized larger than [stopsCircleLayer] so it reads clearly as a ring around
     * the dot at every zoom.
     */
    fun selectedStopLayer(): CircleLayer =
        CircleLayer(LAYER_SELECTED_STOP, SOURCE_SELECTED_STOP).apply {
            setProperties(
                circleRadius(
                    interpolate(
                        linear(),
                        zoom(),
                        stop(11, literal(9.0f)),
                        stop(14, literal(13.0f)),
                        stop(18, literal(20.0f)),
                    ),
                ),
                circleColor(color(android.graphics.Color.parseColor(SELECTED_STOP_COLOR))),
                circleOpacity(0.28f),
                circleStrokeColor(color(android.graphics.Color.parseColor(SELECTED_STOP_COLOR))),
                circleStrokeWidth(2.5f),
            )
        }

    fun stopsToFeatureCollection(stops: List<Stop>): FeatureCollection {
        val features = stops.map { stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.lon, stop.lat)).apply {
                addStringProperty(STOP_PROPERTY_ID, stop.id)
                addStringProperty(STOP_PROPERTY_NAME, stop.name)
            }
        }
        return FeatureCollection.fromFeatures(features)
    }
}

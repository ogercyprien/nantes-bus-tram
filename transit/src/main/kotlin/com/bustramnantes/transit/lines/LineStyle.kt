package com.bustramnantes.transit.lines

import com.bustramnantes.transit.RouteMode
import com.bustramnantes.transit.TransitNetwork

/**
 * How each line is drawn: its [Kind] and its width by zoom, in dp. The builder uses the same widths
 * to decide what overlaps (two corridors merge where their bundles would touch as drawn), so the
 * layout and the drawing agree. Values were matched by eye against the official raster tiles
 * (`linetilesprototye/` comparisons); MapLibre draws them through the same interpolation stops.
 */
class LineStyle(private val routes: List<MapRoute>) {

    /**
     * - [TRAM]: the widest, laid down first, never moved by a bus ([LineMapBuilder] node ranks).
     * - [MAJOR]: Busway (4, 5) and Chronobus (C1...): wider than buses, drawn above them.
     * - [BUS]: every other line.
     * - [BOAT]: Navibus and bacs, dashed (they cross water, not streets).
     * - [HIDDEN]: not drawn, like on the official map: school runs (< [MIN_TRIPS] trips in the
     *   feed: the 1xx lines, LCE, CNO) and the night lines [NIGHT]. Their stops still exist, the
     *   lines just aren't on the map.
     */
    enum class Kind(val code: Int) { TRAM(0), MAJOR(1), BUS(2), BOAT(3), HIDDEN(4) }

    val kind: Array<Kind> = Array(routes.size) { routes[it].kind }

    /**
     * Chronobus lines (C1, C2...), drawn as on the official map: a [CHRONOBUS_EDGE] edge
     * ([CHRONOBUS_EDGE_SHARE] of the width on each side), the line colour, and a white stripe down
     * the middle ([CHRONOBUS_STRIPE_SHARE]). Every other line is plain, in a white casing.
     */
    val chronobus = BooleanArray(routes.size) { kind[it] == Kind.MAJOR && CHRONOBUS.matches(routes[it].shortName) }

    /** Line width (dp) at [zoom]. */
    fun width(route: Int, zoom: Double): Double = widthOf(kind[route], zoom)

    /** Space between two lanes of a bundle (dp); also the white casing's extra width. */
    fun gap(zoom: Double): Double = interp(zoom, GAP)

    /** Width of a bundle of [routes] side by side (dp). */
    fun bundleWidth(routes: IntArray, zoom: Double): Double =
        routes.sumOf { width(it, zoom) } + gap(zoom) * (routes.size - 1).coerceAtLeast(0)

    /**
     * Drawing priority: trams over Busway/Chronobus over buses. Where lines cross, the more important
     * one is on top, as on the official map; it is also the node rank of [LineMapBuilder].
     */
    fun rank(route: Int): Int = when (kind[route]) {
        Kind.TRAM -> 2
        Kind.MAJOR -> 1
        else -> 0
    }

    companion object {
        /** Night lines: a few runs a week, not on the official map either. */
        val NIGHT = setOf("NC", "NN", "NO", "NS")

        /** A line with fewer trips in the whole feed is a school run (not on the official map). */
        const val MIN_TRIPS = 20

        /** The official map's Chronobus edge colour (sampled from its tiles: the same for every line). */
        const val CHRONOBUS_EDGE = 0x6B2359
        const val CHRONOBUS_EDGE_SHARE = 0.12
        const val CHRONOBUS_STRIPE_SHARE = 0.12

        private val CHRONOBUS = Regex("C\\d+")

        /** Busway and Chronobus: drawn wider, laid down right after the trams. */
        fun isMajor(shortName: String): Boolean = shortName == "4" || shortName == "5" || CHRONOBUS.matches(shortName)

        /**
         * Width stops (zoom to dp) per kind, linear in between and flat outside. Matched to the official
         * tiles at z12-z16; the app's MapLibre layers interpolate between the same stops.
         */
        val TRAM_WIDTH = listOf(11.0 to 3.0, 12.0 to 4.5, 14.0 to 9.0, 15.0 to 12.0, 16.0 to 15.0)
        val MAJOR_WIDTH = listOf(11.0 to 2.4, 12.0 to 3.5, 14.0 to 7.0, 15.0 to 9.0, 16.0 to 11.0)
        val BUS_WIDTH = listOf(11.0 to 1.2, 12.0 to 1.8, 14.0 to 4.0, 15.0 to 5.5, 16.0 to 7.0)
        val GAP = listOf(11.0 to 0.6, 12.0 to 0.8, 14.0 to 1.5, 16.0 to 2.5)

        fun widthStops(kind: Kind): List<Pair<Double, Double>> = when (kind) {
            Kind.TRAM -> TRAM_WIDTH
            Kind.MAJOR -> MAJOR_WIDTH
            Kind.BUS, Kind.BOAT -> BUS_WIDTH
            Kind.HIDDEN -> listOf(0.0 to 0.0)
        }

        fun widthOf(kind: Kind, zoom: Double): Double = interp(zoom, widthStops(kind))

        /** The [Kind] of every route of [net], in network order. */
        fun kindsOf(net: TransitNetwork): Array<Kind> {
            val trips = IntArray(net.routes.size)
            for (t in 0 until net.tripCount) trips[net.patterns[net.tripPattern[t]].route]++
            return Array(net.routes.size) { r ->
                val route = net.routes[r]
                when {
                    route.mode == RouteMode.TRAM -> Kind.TRAM
                    route.mode == RouteMode.FERRY -> Kind.BOAT
                    isMajor(route.shortName) -> Kind.MAJOR
                    route.shortName in NIGHT -> Kind.HIDDEN
                    trips[r] < MIN_TRIPS -> Kind.HIDDEN
                    else -> Kind.BUS
                }
            }
        }

        fun interp(z: Double, s: List<Pair<Double, Double>>): Double {
            if (z <= s.first().first) return s.first().second
            for (i in 1 until s.size) if (z <= s[i].first) {
                val (z0, v0) = s[i - 1]; val (z1, v1) = s[i]
                return v0 + (v1 - v0) * (z - z0) / (z1 - z0)
            }
            return s.last().second
        }
    }
}

package com.bustramnantes.transit.lines

import com.bustramnantes.transit.TransitNetwork
import kotlin.math.floor

/**
 * The line maps of one network, one per layout zoom [LAYOUT_ZOOMS], as shipped in
 * `assets/seed/line_map.bin` and rebuilt on the device after a GTFS refresh ([LineMapFormat]).
 * [feedId] is the network's ([TransitNetwork.feedId]): a set belongs to the network it was built from.
 *
 * **Why four layouts.** A layout merges corridors whose bundles would overlap *as drawn at its zoom*,
 * so one layout can't serve every zoom: z12's merge distance makes the whole centre one bundle, which
 * at z15 would put lines on the wrong street (at z14 the merge distance, about 60 m, folds the corner
 * of Rue d'Orléans into Place du Cirque and C3/C8 seem to go round the square; z15 keeps them apart).
 * Above z15, streets are far enough apart that the z15 layout holds and only the widths grow; a z16
 * layout tangled Pirmil (its quays became separate junctions). Below z12 the z12 layout is drawn
 * (the network is a few pixels wide there anyway).
 */
class LineMapSet(val feedId: String, val layouts: Map<Int, LineMap>) {

    /** The layout to draw at [zoom]: its integer part, clamped to [LAYOUT_ZOOMS]. */
    fun layoutFor(zoom: Double): LineMap {
        val z = floor(zoom).toInt().coerceIn(LAYOUT_ZOOMS.first, LAYOUT_ZOOMS.last)
        return layouts[z] ?: layouts.getValue(layouts.keys.minBy { kotlin.math.abs(it - z) })
    }

    companion object {
        val LAYOUT_ZOOMS = 12..15

        /**
         * Builds every layout of [net] (about 11 s on a desktop JVM for the four, most of it at z15).
         * [log] receives each step's statistics.
         */
        fun build(net: TransitNetwork, zooms: IntRange = LAYOUT_ZOOMS, log: (String) -> Unit = {}): LineMapSet {
            val routes = MapRoute.of(net)
            val layouts = LinkedHashMap<Int, LineMap>()
            for (z in zooms) {
                log("layout z$z")
                layouts[z] = LineMapBuilder(net, z, routes).build { log("  $it") }
            }
            return LineMapSet(net.feedId, layouts)
        }
    }
}

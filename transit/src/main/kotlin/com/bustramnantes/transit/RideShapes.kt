package com.bustramnantes.transit

import java.util.Arrays

/**
 * The street path a vehicle takes between two consecutive stops, for every stop-to-stop hop of the
 * network - what a ride is drawn along. The GTFS shapes are no use for that: Naolib publishes one
 * point per stop, so a ride drawn from them cuts through buildings.
 *
 * Built at seed time from OpenStreetMap (`./gradlew :transit:buildSeed`, see
 * `tools/RideShapeBuilder`) and stored in [NetworkFormat] v3.
 *
 * Keyed by the **quay numbers** of the two stops (`FR_NAOLIB:Quay:<n>`, stable across GTFS
 * publications), not by quay index: a GTFS converted on the phone keeps every hop it shares with the
 * previous network ([carriedOver]); only a hop that is really new is drawn straight until the next
 * seed. Only the **interior** points are stored - the two ends are the quays themselves, taken from
 * the current network, so a stop moved by a few metres in a new GTFS still sits on its line.
 *
 * Coordinates are micro-degrees; [keys] are sorted, `points(i)` spans `start[i] until start[i + 1]`.
 */
class RideShapes(
    /** `fromQuayNumber shl 32 or toQuayNumber`, sorted ascending. */
    val keys: LongArray,
    val start: IntArray,
    val lat: IntArray,
    val lon: IntArray,
) {
    init {
        require(start.size == keys.size + 1 && start.last() == lat.size && lat.size == lon.size)
    }

    val size: Int get() = keys.size
    val pointCount: Int get() = lat.size

    /** Index of the hop between quay numbers [from] and [to], or -1. */
    fun indexOf(from: Int, to: Int): Int = Arrays.binarySearch(keys, key(from, to)).let { if (it >= 0) it else -1 }

    /** Interior points of hop [i], (lat, lon) interleaved, in degrees. */
    fun interior(i: Int): DoubleArray {
        val a = start[i]; val b = start[i + 1]
        return DoubleArray((b - a) * 2) { k -> if (k % 2 == 0) lat[a + k / 2] / 1e6 else lon[a + k / 2] / 1e6 }
    }

    companion object {
        fun key(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xffffffffL)

        val EMPTY = RideShapes(LongArray(0), IntArray(1), IntArray(0), IntArray(0))

        /** From (key -> interior points, (lat, lon) interleaved in degrees). */
        fun of(hops: Map<Long, DoubleArray>): RideShapes {
            val sorted = hops.keys.sorted()
            val start = IntArray(sorted.size + 1)
            sorted.forEachIndexed { k, key -> start[k + 1] = start[k] + hops.getValue(key).size / 2 }
            val la = IntArray(start.last()); val lo = IntArray(start.last())
            sorted.forEachIndexed { k, key ->
                val pts = hops.getValue(key)
                for (j in 0 until pts.size / 2) {
                    la[start[k] + j] = Math.round(pts[2 * j] * 1e6).toInt()
                    lo[start[k] + j] = Math.round(pts[2 * j + 1] * 1e6).toInt()
                }
            }
            return RideShapes(sorted.toLongArray(), start, la, lo)
        }

        /**
         * The hops [network] uses, taken from the first of [sources] that has each one - how a network
         * converted on the device (no OSM there) keeps its street paths, and how a network downloaded
         * by an older version gets the seed's. Null when none is found.
         */
        fun carriedOver(network: TransitNetwork, vararg sources: TransitNetwork?): RideShapes? {
            val used = network.hopKeys()
            val hops = HashMap<Long, DoubleArray>()
            for (src in sources) {
                val shapes = src?.rideShapes ?: continue
                for (i in 0 until shapes.size) if (shapes.keys[i] in used && shapes.keys[i] !in hops) hops[shapes.keys[i]] = shapes.interior(i)
            }
            return if (hops.isEmpty()) null else of(hops)
        }
    }
}

/** Every (from, to) quay-number pair consecutive in some pattern of this network. */
fun TransitNetwork.hopKeys(): Set<Long> {
    val out = HashSet<Long>()
    for (p in patterns) for (k in 0 until p.quays.size - 1) out += RideShapes.key(quayNumber[p.quays[k]], quayNumber[p.quays[k + 1]])
    return out
}

/**
 * The path of a ride on [pattern] from position [from] to position [to] (inclusive), (lat, lon)
 * pairs: every quay, with each hop's street path between them when [TransitNetwork.rideShapes] has
 * it, a straight line otherwise (ferries, a hop newer than the shapes).
 */
fun TransitNetwork.ridePath(pattern: Pattern, from: Int, to: Int): List<DoubleArray> {
    val shapes = rideShapes
    val out = ArrayList<DoubleArray>()
    for (pos in from..to) {
        val q = pattern.quays[pos]
        out += doubleArrayOf(quayLat[q], quayLon[q])
        if (pos == to || shapes == null) continue
        val i = shapes.indexOf(quayNumber[q], quayNumber[pattern.quays[pos + 1]])
        if (i < 0) continue
        val pts = shapes.interior(i)
        for (k in 0 until pts.size / 2) out += doubleArrayOf(pts[2 * k], pts[2 * k + 1])
    }
    return out
}

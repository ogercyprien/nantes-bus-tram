package com.bustramnantes.transit.lines

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

/**
 * The packed binary form of a [LineMapSet]: `assets/seed/line_map.bin`, and what the app writes after
 * rebuilding the layouts for a downloaded network.
 *
 * A separate file rather than a section of `network.bin`: the layouts are derived from the network
 * (any network can have them rebuilt), take seconds to compute rather than to read, and the app swaps
 * them independently (the previous network's map stays on screen while the new one is computed).
 *
 * Layout: magic `NAOLLMAP`, a big-endian int [VERSION], then one deflate stream: the feed id, the
 * routes (id, short name, colour, [LineStyle.Kind] code), the stop ids (a string table), then per
 * layout its zoom, nodes, segments (ends, lane order, centreline), transitions and stop markers.
 * Coordinates are Web Mercator **decimetres**, delta-encoded within each segment (LEB128 varints,
 * zig-zag for signed values), angles in milliradians. Bump [VERSION] on any layout change: a reader
 * refuses unknown versions and the app rebuilds the layouts.
 */
object LineMapFormat {
    const val VERSION = 1
    private val MAGIC = "NAOLLMAP".toByteArray(Charsets.US_ASCII)

    fun write(set: LineMapSet, out: OutputStream) {
        out.write(MAGIC)
        DataOutputStream(out).writeInt(VERSION)
        val deflated = DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION), 1 shl 16)
        val d = DataOutputStream(BufferedOutputStream(deflated, 1 shl 16))
        d.writeUTF(set.feedId)
        val routes = set.layouts.values.first().routes
        d.varint(routes.size)
        for (r in routes) { d.writeUTF(r.id); d.writeUTF(r.shortName); d.writeUTF(r.color); d.varint(r.kind.code) }
        val stopIds = set.layouts.values.flatMap { m -> m.stops.map { it.stopId } }.distinct()
        val stopIndex = stopIds.withIndex().associate { it.value to it.index }
        d.varint(stopIds.size)
        for (id in stopIds) d.writeUTF(id)
        d.varint(set.layouts.size)
        for ((zoom, map) in set.layouts) {
            d.varint(zoom)
            d.varint(map.nodeX.size)
            var px = 0; var py = 0
            for (i in map.nodeX.indices) {
                val x = dm(map.nodeX[i]); val y = dm(map.nodeY[i])
                d.zigzag(x - px); d.zigzag(y - py); px = x; py = y
            }
            d.varint(map.segments.size)
            for (s in map.segments) {
                d.varint(s.first); d.varint(s.last)
                d.varint(s.order.size); for (r in s.order) d.varint(r)
                d.varint(s.x.size)
                var lx = 0; var ly = 0
                for (i in s.x.indices) {
                    val x = dm(s.x[i]); val y = dm(s.y[i])
                    d.zigzag(x - lx); d.zigzag(y - ly); lx = x; ly = y
                }
            }
            d.varint(map.transitions.size)
            for (t in map.transitions) { d.varint(t.node); d.varint(t.endA); d.varint(t.endB); d.varint(t.route) }
            d.varint(map.stops.size)
            for (m in map.stops) {
                d.varint(stopIndex.getValue(m.stopId))
                d.zigzag(dm(m.x)); d.zigzag(dm(m.y))
                d.writeBoolean(m.hub)
                if (m.hub) { d.zigzag(m.dashSegment); d.zigzag((m.dashAngle * 1000).roundToInt()) }
            }
        }
        d.flush()
        deflated.finish()
        out.flush()
    }

    fun read(input: InputStream): LineMapSet {
        val raw = BufferedInputStream(input)
        val magic = ByteArray(MAGIC.size)
        DataInputStream(raw).readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("not a line map")
        val version = DataInputStream(raw).readInt()
        if (version != VERSION) throw IOException("line map version $version, expected $VERSION")
        val d = DataInputStream(BufferedInputStream(InflaterInputStream(raw), 1 shl 16))
        val feedId = d.readUTF()
        val kinds = LineStyle.Kind.entries.associateBy { it.code }
        val routes = List(d.varint()) { MapRoute(d.readUTF(), d.readUTF(), d.readUTF(), kinds[d.varint()] ?: throw IOException("bad kind")) }
        val stopIds = Array(d.varint()) { d.readUTF() }
        val layouts = LinkedHashMap<Int, LineMap>()
        repeat(d.varint()) {
            val zoom = d.varint()
            val n = d.varint()
            val nodeX = DoubleArray(n); val nodeY = DoubleArray(n)
            var px = 0; var py = 0
            for (i in 0 until n) { px += d.zigzag(); py += d.zigzag(); nodeX[i] = px / 10.0; nodeY[i] = py / 10.0 }
            val segments = List(d.varint()) {
                val first = d.varint(); val last = d.varint()
                val order = IntArray(d.varint()) { d.varint() }
                val m = d.varint()
                val x = DoubleArray(m); val y = DoubleArray(m)
                var lx = 0; var ly = 0
                for (i in 0 until m) { lx += d.zigzag(); ly += d.zigzag(); x[i] = lx / 10.0; y[i] = ly / 10.0 }
                LineMap.Segment(first, last, x, y, order)
            }
            val transitions = List(d.varint()) { LineMap.Transition(d.varint(), d.varint(), d.varint(), d.varint()) }
            val stops = List(d.varint()) {
                val id = stopIds[d.varint()]
                val x = d.zigzag() / 10.0; val y = d.zigzag() / 10.0
                val hub = d.readBoolean()
                if (hub) LineMap.StopMarker(id, x, y, true, d.zigzag(), d.zigzag() / 1000.0) else LineMap.StopMarker(id, x, y, false)
            }
            layouts[zoom] = LineMap(zoom, routes, nodeX, nodeY, segments, transitions, stops)
        }
        return LineMapSet(feedId, layouts)
    }

    private fun dm(v: Double): Int = (v * 10).roundToInt()

    private fun DataOutputStream.varint(value: Int) {
        var v = value
        while (v and 0x7f.inv() != 0) { writeByte((v and 0x7f) or 0x80); v = v ushr 7 }
        writeByte(v)
    }

    private fun DataOutputStream.zigzag(value: Int) = varint((value shl 1) xor (value shr 31))

    private fun DataInputStream.varint(): Int {
        var shift = 0; var result = 0
        while (true) {
            val b = readUnsignedByte()
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun DataInputStream.zigzag(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }
}

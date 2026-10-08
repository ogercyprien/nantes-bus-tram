package com.bustramnantes.transit.tools

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.zip.Inflater

/**
 * A minimal streaming reader for OpenStreetMap `.osm.pbf` extracts (Geofabrik), build-time only
 * (`./gradlew :transit:packWalkGraph`). Hand-written like the GTFS-RT decoder so `:transit` keeps no
 * protobuf dependency. Reads only what the walking-graph builder needs: dense nodes (id, lat, lon),
 * ways (id, tags, node refs) and relations (id, tags, members). Plain (non-dense) nodes are read too.
 *
 * Format (https://wiki.openstreetmap.org/wiki/PBF_Format): a sequence of
 * `int32 BE length, BlobHeader{1 type, 3 datasize}, Blob{1 raw | 3 zlib_data}`; `OSMData` blobs are a
 * `PrimitiveBlock{1 stringtable{1 s*}, 2 primitivegroup*, 17 granularity=100, 19 lat_offset,
 * 20 lon_offset}`; `PrimitiveGroup{1 node*, 2 dense, 3 way*, 4 relation*}`;
 * `DenseNodes{1 id (sint64 delta), 8 lat, 9 lon (sint64 delta), 10 keys_vals}`;
 * `Way{1 id, 2 keys, 3 vals, 8 refs (sint64 delta)}`;
 * `Relation{1 id, 2 keys, 3 vals, 8 roles_sid, 9 memids (sint64 delta), 10 types}`.
 * Coordinates are returned in 1e-7 degrees (`lat_offset + granularity * value` is in 1e-9).
 */
class OsmPbfReader(private val file: File) {

    interface Visitor {
        /** Called once per block before its elements; return false to skip decoding nodes in it. */
        fun wantNodes(): Boolean = false
        fun wantWays(): Boolean = false
        fun wantRelations(): Boolean = false
        fun node(id: Long, lat7: Int, lon7: Int) {}
        fun way(id: Long, tags: Map<String, String>, refs: LongArray) {}
        fun relation(id: Long, tags: Map<String, String>, memberIds: LongArray, memberTypes: IntArray, roles: Array<String>) {}
    }

    fun read(visitor: Visitor) {
        DataInputStream(file.inputStream().buffered(1 shl 20)).use { input ->
            val inflater = Inflater()
            var buffer = ByteArray(1 shl 20)
            while (true) {
                val headerLength = try { input.readInt() } catch (e: EOFException) { break }
                val header = ByteArray(headerLength).also(input::readFully)
                var type = ""
                var dataSize = 0
                Pb(header).forEach { f, pb ->
                    when (f) {
                        1 -> type = pb.string()
                        3 -> dataSize = pb.varint().toInt()
                        else -> pb.skip()
                    }
                }
                val blob = ByteArray(dataSize).also(input::readFully)
                if (type != "OSMData") continue
                var raw: ByteArray? = null
                var rawSize = 0
                var zStart = -1
                var zLen = 0
                Pb(blob).forEach { f, pb ->
                    when (f) {
                        1 -> raw = pb.bytes()
                        2 -> rawSize = pb.varint().toInt()
                        3 -> { zLen = pb.lengthPrefix(); zStart = pb.pos; pb.pos += zLen }
                        else -> pb.skip()
                    }
                }
                val data: ByteArray
                val dataLength: Int
                if (raw != null) {
                    data = raw!!; dataLength = data.size
                } else {
                    if (buffer.size < rawSize) buffer = ByteArray(rawSize)
                    inflater.reset()
                    inflater.setInput(blob, zStart, zLen)
                    var n = 0
                    while (n < rawSize) {
                        val k = inflater.inflate(buffer, n, rawSize - n)
                        if (k == 0 && (inflater.finished() || inflater.needsInput())) break
                        n += k
                    }
                    data = buffer; dataLength = rawSize
                }
                block(Pb(data, 0, dataLength), visitor)
            }
            inflater.end()
        }
    }

    private fun block(pb: Pb, visitor: Visitor) {
        val strings = ArrayList<String>()
        val groups = ArrayList<IntArray>() // (start, end) of each group
        var granularity = 100L
        var latOffset = 0L
        var lonOffset = 0L
        pb.forEach { f, p ->
            when (f) {
                1 -> {
                    val end = p.lengthPrefix() + p.pos
                    while (p.pos < end) {
                        p.varint() // tag (field 1, wire type 2)
                        strings += p.string()
                    }
                }
                2 -> { val len = p.lengthPrefix(); groups += intArrayOf(p.pos, p.pos + len); p.pos += len }
                17 -> granularity = p.varint()
                19 -> latOffset = p.varint()
                20 -> lonOffset = p.varint()
                else -> p.skip()
            }
        }
        val wantNodes = visitor.wantNodes()
        val wantWays = visitor.wantWays()
        val wantRelations = visitor.wantRelations()
        for ((start, end) in groups.map { it[0] to it[1] }) {
            Pb(pb.buf, start, end).forEach { f, g ->
                when {
                    f == 2 && wantNodes -> dense(g.sub(), visitor, granularity, latOffset, lonOffset)
                    f == 1 && wantNodes -> node(g.sub(), visitor, granularity, latOffset, lonOffset)
                    f == 3 && wantWays -> way(g.sub(), visitor, strings)
                    f == 4 && wantRelations -> relation(g.sub(), visitor, strings)
                    else -> g.skip()
                }
            }
        }
    }

    private fun coord7(offset: Long, granularity: Long, value: Long): Int = ((offset + granularity * value) / 100).toInt()

    private fun dense(pb: Pb, visitor: Visitor, gran: Long, latOff: Long, lonOff: Long) {
        var ids: LongArray? = null
        var lats: LongArray? = null
        var lons: LongArray? = null
        pb.forEach { f, p ->
            when (f) {
                1 -> ids = p.packedSint64Delta()
                8 -> lats = p.packedSint64Delta()
                9 -> lons = p.packedSint64Delta()
                else -> p.skip()
            }
        }
        val i = ids ?: return
        val la = lats!!
        val lo = lons!!
        for (k in i.indices) visitor.node(i[k], coord7(latOff, gran, la[k]), coord7(lonOff, gran, lo[k]))
    }

    private fun node(pb: Pb, visitor: Visitor, gran: Long, latOff: Long, lonOff: Long) {
        var id = 0L
        var lat = 0L
        var lon = 0L
        pb.forEach { f, p ->
            when (f) {
                1 -> id = p.sint64()
                8 -> lat = p.sint64()
                9 -> lon = p.sint64()
                else -> p.skip()
            }
        }
        visitor.node(id, coord7(latOff, gran, lat), coord7(lonOff, gran, lon))
    }

    private fun tags(keys: IntArray?, vals: IntArray?, strings: List<String>): Map<String, String> {
        if (keys == null || vals == null) return emptyMap()
        val m = HashMap<String, String>(keys.size * 2)
        for (k in keys.indices) m[strings[keys[k]]] = strings[vals[k]]
        return m
    }

    private fun way(pb: Pb, visitor: Visitor, strings: List<String>) {
        var id = 0L
        var keys: IntArray? = null
        var vals: IntArray? = null
        var refs = LongArray(0)
        pb.forEach { f, p ->
            when (f) {
                1 -> id = p.varint()
                2 -> keys = p.packedInt()
                3 -> vals = p.packedInt()
                8 -> refs = p.packedSint64Delta()
                else -> p.skip()
            }
        }
        visitor.way(id, tags(keys, vals, strings), refs)
    }

    private fun relation(pb: Pb, visitor: Visitor, strings: List<String>) {
        var id = 0L
        var keys: IntArray? = null
        var vals: IntArray? = null
        var roles = IntArray(0)
        var memids = LongArray(0)
        var types = IntArray(0)
        pb.forEach { f, p ->
            when (f) {
                1 -> id = p.varint()
                2 -> keys = p.packedInt()
                3 -> vals = p.packedInt()
                8 -> roles = p.packedInt()
                9 -> memids = p.packedSint64Delta()
                10 -> types = p.packedInt()
                else -> p.skip()
            }
        }
        visitor.relation(id, tags(keys, vals, strings), memids, types, Array(roles.size) { strings[roles[it]] })
    }
}

/** Protobuf wire reader over `buf[pos, end)`, allocation-light (one instance per message). */
internal class Pb(val buf: ByteArray, var pos: Int = 0, private val end: Int = buf.size) {
    private var wire = 0

    inline fun forEach(block: (field: Int, pb: Pb) -> Unit) {
        while (hasMore()) block(nextTag(), this)
    }

    fun hasMore(): Boolean = pos < end

    fun nextTag(): Int {
        val tag = varint().toInt()
        wire = tag and 7
        return tag ushr 3
    }

    fun varint(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val b = buf[pos++].toInt()
            r = r or ((b and 0x7F).toLong() shl s)
            if (b and 0x80 == 0) return r
            s += 7
        }
    }

    fun sint64(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }
    fun lengthPrefix(): Int = varint().toInt()
    fun string(): String { val n = lengthPrefix(); val s = String(buf, pos, n, Charsets.UTF_8); pos += n; return s }
    fun bytes(): ByteArray { val n = lengthPrefix(); val b = buf.copyOfRange(pos, pos + n); pos += n; return b }
    fun sub(): Pb { val n = lengthPrefix(); val p = Pb(buf, pos, pos + n); pos += n; return p }

    fun skip() {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val n = lengthPrefix(); pos += n }
            5 -> pos += 4
            else -> error("wire type $wire")
        }
    }

    fun packedInt(): IntArray {
        val n = lengthPrefix()
        val stop = pos + n
        var out = IntArray(16)
        var c = 0
        while (pos < stop) {
            if (c == out.size) out = out.copyOf(c * 2)
            out[c++] = varint().toInt()
        }
        return out.copyOf(c)
    }

    fun packedSint64Delta(): LongArray {
        val n = lengthPrefix()
        val stop = pos + n
        var out = LongArray(64)
        var c = 0
        var acc = 0L
        while (pos < stop) {
            if (c == out.size) out = out.copyOf(c * 2)
            acc += sint64()
            out[c++] = acc
        }
        return out.copyOf(c)
    }
}

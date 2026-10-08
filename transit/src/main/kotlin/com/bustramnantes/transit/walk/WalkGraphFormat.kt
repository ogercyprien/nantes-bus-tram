package com.bustramnantes.transit.walk

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

/**
 * Packed [WalkGraph] (`assets/seed/walk_graph.bin`): magic `NAOLWLK1`, big-endian int [VERSION], then
 * one deflate stream: source label (UTF), vertex count, vertices as zig-zag deltas of micro-degrees,
 * edge count, then per edge (edges sorted by from-vertex): from delta, to − from (zig-zag), length in
 * decimetres, intermediate point count and their zig-zag deltas (starting from the from-vertex).
 * Vertices are ordered spatially by the builder so deltas stay small.
 */
object WalkGraphFormat {
    const val VERSION = 1
    private val MAGIC = "NAOLWLK1".toByteArray(Charsets.US_ASCII)

    fun write(g: WalkGraph, out: OutputStream) {
        out.write(MAGIC)
        DataOutputStream(out).writeInt(VERSION)
        val deflated = DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION), 1 shl 16)
        val d = DataOutputStream(BufferedOutputStream(deflated, 1 shl 16))
        d.writeUTF(g.source)
        d.varint(g.vertexCount)
        var pLat = 0; var pLon = 0
        for (v in 0 until g.vertexCount) {
            d.zigzag(g.vertexLat[v] - pLat); d.zigzag(g.vertexLon[v] - pLon)
            pLat = g.vertexLat[v]; pLon = g.vertexLon[v]
        }
        val order = (0 until g.edgeCount).sortedWith(compareBy({ g.edgeFrom[it] }, { g.edgeTo[it] }))
        d.varint(g.edgeCount)
        var pFrom = 0
        for (e in order) {
            val from = g.edgeFrom[e]
            d.varint(from - pFrom); pFrom = from
            d.zigzag(g.edgeTo[e] - from)
            d.varint(Math.round(g.edgeLength[e] * 10f))
            val a = g.geomStart[e]; val b = g.geomStart[e + 1]
            d.varint(b - a)
            var la = g.vertexLat[from]; var lo = g.vertexLon[from]
            for (i in a until b) { d.zigzag(g.geomLat[i] - la); d.zigzag(g.geomLon[i] - lo); la = g.geomLat[i]; lo = g.geomLon[i] }
        }
        d.flush()
        deflated.finish()
        out.flush()
    }

    fun read(input: InputStream): WalkGraph {
        val raw = DataInputStream(BufferedInputStream(input, 1 shl 16))
        val magic = ByteArray(MAGIC.size).also(raw::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("not a walk graph file")
        val version = raw.readInt()
        if (version != VERSION) throw IOException("walk graph version $version, expected $VERSION")
        val d = DataInputStream(BufferedInputStream(InflaterInputStream(raw), 1 shl 16))
        val source = d.readUTF()
        val n = d.varint()
        val lat = IntArray(n); val lon = IntArray(n)
        var pLat = 0; var pLon = 0
        for (v in 0 until n) { pLat += d.zigzag(); pLon += d.zigzag(); lat[v] = pLat; lon[v] = pLon }
        val m = d.varint()
        val from = IntArray(m); val to = IntArray(m); val len = FloatArray(m)
        val start = IntArray(m + 1)
        val gLat = IntList(); val gLon = IntList()
        var pFrom = 0
        for (e in 0 until m) {
            pFrom += d.varint()
            from[e] = pFrom
            to[e] = pFrom + d.zigzag()
            len[e] = d.varint() / 10f
            val k = d.varint()
            var la = lat[pFrom]; var lo = lon[pFrom]
            repeat(k) { la += d.zigzag(); lo += d.zigzag(); gLat.add(la); gLon.add(lo) }
            start[e + 1] = gLat.size
        }
        return WalkGraph(source, lat, lon, from, to, len, start, gLat.toArray(), gLon.toArray())
    }

    private fun DataOutputStream.varint(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { writeByte((v and 0x7F) or 0x80); v = v ushr 7 }
        writeByte(v)
    }

    private fun DataOutputStream.zigzag(value: Int) = varint((value shl 1) xor (value shr 31))

    private fun DataInputStream.varint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun DataInputStream.zigzag(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }
}

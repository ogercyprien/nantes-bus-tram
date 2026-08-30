package com.bustramnantes.transit

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

/**
 * The packed binary form of a [TransitNetwork] - what ships as `assets/seed/network.bin` and what the
 * on-device refresh writes to `filesDir/network/`.
 *
 * Layout: an 8-byte magic `NAOLNETW`, a big-endian int [VERSION], then one deflate stream holding the
 * sections in [TransitNetwork] field order. Integers are LEB128 varints (zig-zag for signed deltas),
 * coordinates are micro-degrees delta-encoded, strings are `writeUTF`. Trips are sorted by (pattern,
 * start) so pattern indices and start times delta-encode to ~1 byte each. Bump [VERSION] on any
 * layout change: a reader refuses unknown versions and the app falls back to the bundled seed.
 *
 * Versions: **1** = routes, stop places, quays, patterns, trips and shapes.
 */
object NetworkFormat {
    const val VERSION = 1
    private val READABLE = setOf(1)
    private val MAGIC = "NAOLNETW".toByteArray(Charsets.US_ASCII)

    fun write(network: TransitNetwork, out: OutputStream) {
        out.write(MAGIC)
        DataOutputStream(out).writeInt(VERSION)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val deflated = DeflaterOutputStream(out, deflater, 1 shl 16)
        val d = DataOutputStream(BufferedOutputStream(deflated, 1 shl 16))
        with(network) {
            d.writeUTF(feedId)
            d.varint(validFrom.toEpochDay().toInt())
            d.varint(dayCount)
            d.writeUTF(timezone)

            d.varint(routes.size)
            for (r in routes) {
                d.writeUTF(r.shortName); d.writeUTF(r.longName); d.writeUTF(r.color); d.writeUTF(r.textColor)
                d.varint(r.mode.gtfsType); d.zigzag(r.sortOrder)
                d.nullableUtf(r.directionNames[0]); d.nullableUtf(r.directionNames[1])
            }

            d.varint(stopPlaces.size)
            var pLat = 0; var pLon = 0
            for (sp in stopPlaces) {
                d.varint(sp.number); d.writeUTF(sp.name)
                val la = micro(sp.lat); val lo = micro(sp.lon)
                d.zigzag(la - pLat); d.zigzag(lo - pLon); pLat = la; pLon = lo
                d.writeBoolean(sp.wheelchair); d.writeBoolean(sp.hasDisplayPosition)
                d.varint(sp.aliases.size); sp.aliases.forEach(d::writeUTF)
            }

            d.varint(quayCount)
            var qLat = 0; var qLon = 0
            for (q in 0 until quayCount) {
                d.varint(quayNumber[q]); d.varint(quayStopPlace[q])
                val la = micro(quayLat[q]); val lo = micro(quayLon[q])
                d.zigzag(la - qLat); d.zigzag(lo - qLon); qLat = la; qLon = lo
            }

            d.varint(headsigns.size); headsigns.forEach(d::writeUTF)

            d.varint(patterns.size)
            for (p in patterns) {
                d.varint(p.route); d.varint(p.directionId); d.writeUTF(p.headsign)
                d.varint(p.quays.size); for (q in p.quays) d.varint(q)
                d.varint(p.shape + 1)
            }

            d.varint(profileArrivals.size)
            for (i in profileArrivals.indices) {
                val a = profileArrivals[i]; val dep = profileDepartures[i]
                d.varint(a.size)
                var prev = 0
                for (k in a.indices) { d.zigzag(a[k] - prev); d.zigzag(dep[k] - a[k]); prev = dep[k] }
            }

            val words = serviceSets.firstOrNull()?.size ?: 0
            d.varint(serviceSets.size); d.varint(words)
            for (set in serviceSets) set.forEach(d::writeLong)

            d.varint(tripCount)
            var prevPattern = 0; var prevStart = 0
            for (t in 0 until tripCount) {
                val p = tripPattern[t]
                if (p != prevPattern) prevStart = 0
                d.varint(p - prevPattern); d.zigzag(tripStart[t] - prevStart)
                prevPattern = p; prevStart = tripStart[t]
                d.varint(tripProfile[t]); d.varint(tripServiceSet[t]); d.varint(tripHeadsign[t])
            }

            d.varint(vjNumbers.size)
            var prevVj = 0
            for (i in vjNumbers.indices) { d.varint(vjNumbers[i] - prevVj); prevVj = vjNumbers[i]; d.varint(vjTrips[i]) }

            d.varint(shapes.size)
            for (shape in shapes) {
                d.varint(shape.size / 2)
                var la0 = 0; var lo0 = 0
                for (k in 0 until shape.size / 2) {
                    val la = micro(shape[2 * k]); val lo = micro(shape[2 * k + 1])
                    d.zigzag(la - la0); d.zigzag(lo - lo0); la0 = la; lo0 = lo
                }
            }
        }
        d.flush()
        // Finish (not close) the deflate stream: `out` belongs to the caller.
        deflated.finish()
        deflater.end()
    }

    fun read(input: InputStream): TransitNetwork {
        val raw = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        val magic = ByteArray(MAGIC.size)
        DataInputStream(raw).readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("not a packed network")
        val version = DataInputStream(raw).readInt()
        if (version !in READABLE) throw IOException("unsupported network format $version")
        val d = DataInputStream(BufferedInputStream(InflaterInputStream(raw), 1 shl 16))

        val feedId = d.readUTF()
        val validFrom = LocalDate.ofEpochDay(d.varint().toLong())
        val dayCount = d.varint()
        val timezone = d.readUTF()

        val routes = List(d.varint()) {
            val short = d.readUTF(); val long = d.readUTF(); val color = d.readUTF(); val text = d.readUTF()
            val mode = RouteMode.fromGtfs(d.varint()); val sort = d.zigzag()
            Route(short, long, color, text, mode, sort, arrayOf(d.nullableUtf(), d.nullableUtf()))
        }

        class RawPlace(val number: Int, val name: String, val lat: Double, val lon: Double, val wheelchair: Boolean, val aliases: List<String>, val display: Boolean)
        var pLat = 0; var pLon = 0
        val places = List(d.varint()) {
            val number = d.varint(); val name = d.readUTF()
            pLat += d.zigzag(); pLon += d.zigzag()
            val wheelchair = d.readBoolean(); val display = d.readBoolean()
            val aliases = List(d.varint()) { d.readUTF() }
            RawPlace(number, name, pLat / 1e6, pLon / 1e6, wheelchair, aliases, display)
        }

        val quayCount = d.varint()
        val quayNumber = IntArray(quayCount); val quayParent = IntArray(quayCount)
        val quayLat = DoubleArray(quayCount); val quayLon = DoubleArray(quayCount)
        var qLat = 0; var qLon = 0
        for (q in 0 until quayCount) {
            quayNumber[q] = d.varint(); quayParent[q] = d.varint()
            qLat += d.zigzag(); qLon += d.zigzag()
            quayLat[q] = qLat / 1e6; quayLon[q] = qLon / 1e6
        }
        val placeQuays = Array(places.size) { ArrayList<Int>() }
        for (q in 0 until quayCount) placeQuays[quayParent[q]] += q
        val stopPlaces = places.mapIndexed { i, p ->
            StopPlace(p.number, p.name, p.lat, p.lon, p.wheelchair, placeQuays[i].toIntArray(), p.aliases, p.display)
        }

        val headsigns = Array(d.varint()) { d.readUTF() }

        val patterns = List(d.varint()) {
            val route = d.varint(); val dir = d.varint(); val hs = d.readUTF()
            val quays = IntArray(d.varint()) { d.varint() }
            Pattern(route, dir, hs, quays, d.varint() - 1)
        }

        val profileCount = d.varint()
        val profileArr = arrayOfNulls<IntArray>(profileCount)
        val profileDep = arrayOfNulls<IntArray>(profileCount)
        for (i in 0 until profileCount) {
            val n = d.varint()
            val a = IntArray(n); val dep = IntArray(n)
            var prev = 0
            for (k in 0 until n) { a[k] = prev + d.zigzag(); dep[k] = a[k] + d.zigzag(); prev = dep[k] }
            profileArr[i] = a; profileDep[i] = dep
        }

        val setCount = d.varint(); val words = d.varint()
        val serviceSets = Array(setCount) { LongArray(words) { d.readLong() } }

        val tripCount = d.varint()
        val tripPattern = IntArray(tripCount); val tripStart = IntArray(tripCount)
        val tripProfile = IntArray(tripCount); val tripSet = IntArray(tripCount); val tripHeadsign = IntArray(tripCount)
        var prevPattern = 0; var prevStart = 0
        for (t in 0 until tripCount) {
            val p = prevPattern + d.varint()
            if (p != prevPattern) prevStart = 0
            val start = prevStart + d.zigzag()
            tripPattern[t] = p; tripStart[t] = start
            prevPattern = p; prevStart = start
            tripProfile[t] = d.varint(); tripSet[t] = d.varint(); tripHeadsign[t] = d.varint()
        }

        val vjCount = d.varint()
        val vjNumbers = IntArray(vjCount); val vjTrips = IntArray(vjCount)
        var prevVj = 0
        for (i in 0 until vjCount) { prevVj += d.varint(); vjNumbers[i] = prevVj; vjTrips[i] = d.varint() }

        val shapes = Array(d.varint()) {
            val n = d.varint()
            val pts = DoubleArray(n * 2)
            var la = 0; var lo = 0
            for (k in 0 until n) { la += d.zigzag(); lo += d.zigzag(); pts[2 * k] = la / 1e6; pts[2 * k + 1] = lo / 1e6 }
            pts
        }

        return TransitNetwork(
            formatVersion = version, feedId = feedId, validFrom = validFrom, dayCount = dayCount, timezone = timezone,
            routes = routes, stopPlaces = stopPlaces,
            quayNumber = quayNumber, quayStopPlace = quayParent, quayLat = quayLat, quayLon = quayLon,
            patterns = patterns,
            tripPattern = tripPattern, tripStart = tripStart, tripProfile = tripProfile,
            tripServiceSet = tripSet, tripHeadsign = tripHeadsign, headsigns = headsigns,
            profileArrivals = profileArr.requireNoNulls(), profileDepartures = profileDep.requireNoNulls(),
            serviceSets = serviceSets, vjNumbers = vjNumbers, vjTrips = vjTrips, shapes = shapes,
        )
    }

    private fun micro(v: Double): Int = (v * 1e6).roundToInt()

    private fun DataOutputStream.varint(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        writeByte(v)
    }

    private fun DataOutputStream.zigzag(value: Int) = varint((value shl 1) xor (value shr 31))

    private fun DataOutputStream.nullableUtf(s: String?) {
        writeBoolean(s != null)
        if (s != null) writeUTF(s)
    }

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

    private fun DataInputStream.zigzag(): Int {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    private fun DataInputStream.nullableUtf(): String? = if (readBoolean()) readUTF() else null
}

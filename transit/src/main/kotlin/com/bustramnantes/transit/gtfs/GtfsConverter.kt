package com.bustramnantes.transit.gtfs

import com.bustramnantes.transit.Pattern
import com.bustramnantes.transit.Route
import com.bustramnantes.transit.RouteMode
import com.bustramnantes.transit.StopPlace
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.NetworkFormat
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipFile

/** Where the converter reads GTFS files from - a zip on disk, or a directory/fixture. */
fun interface GtfsSource {
    /** Opens `name` (e.g. `"stops.txt"`), or returns null when the feed has no such file. */
    fun open(name: String): InputStream?

    companion object {
        fun zip(file: File): GtfsSource = GtfsSource { name ->
            // A fresh ZipFile per open so re-reading (the ungrouped stop_times fallback) just works.
            val zip = ZipFile(file)
            val entry = zip.getEntry(name)
            if (entry == null) {
                zip.close()
                null
            } else {
                object : java.io.FilterInputStream(zip.getInputStream(entry)) {
                    override fun close() {
                        super.close()
                        zip.close()
                    }
                }
            }
        }

        fun directory(dir: File): GtfsSource = GtfsSource { name ->
            File(dir, name).takeIf { it.isFile }?.inputStream()
        }
    }
}

/**
 * GTFS zip -> [TransitNetwork]. Streams every file - `stop_times.txt` is ~583 MB uncompressed and is
 * never materialised: each trip's rows are de-duplicated as soon as the next trip starts (the Naolib
 * file is grouped by trip; if a feed isn't, the conversion transparently restarts in a buffered mode).
 *
 * De-duplication (the feed repeats every vehicle journey once per service-period chunk):
 *  - **pattern** = (route, direction, headsign, quay sequence);
 *  - **profile** = arrival/departure offsets from the first departure;
 *  - **unique trip** = (pattern, first departure, profile); its services are OR-ed into a day bitset.
 * Measured on a real feed: 187,896 trips -> ~42.6 k unique trips, ~300 patterns.
 */
class GtfsConverter(
    private val source: GtfsSource,
    private val feedId: String = "",
) {

    fun convert(): TransitNetwork {
        val routes = readRoutes()
        val stops = readStops()
        val calendar = readCalendar()
        val trips = readTrips(routes.indexById, calendar)
        val times = try {
            readStopTimes(stops, trips, grouped = true)
        } catch (e: UngroupedStopTimesException) {
            readStopTimes(stops, trips, grouped = false)
        }
        val shapes = readShapes(times.patternShapeIds, trips)
        return assemble(routes, stops, calendar, trips, times, shapes)
    }

    // ---------------------------------------------------------------- routes

    private class Routes(val list: List<Route>, val indexById: Map<String, Int>)

    private fun readRoutes(): Routes {
        class Raw(val id: String, val short: String, val long: String, val type: Int, val color: String, val text: String, val sort: Int)
        val raw = ArrayList<Raw>()
        csv("routes.txt") { r ->
            val id = r.requireColumn("route_id")
            val short = r.column("route_short_name")
            val long = r.column("route_long_name")
            val type = r.requireColumn("route_type")
            val color = r.column("route_color")
            val text = r.column("route_text_color")
            val sort = r.column("route_sort_order")
            r.forEachRow { row ->
                val routeId = row[id]
                raw += Raw(
                    id = routeId,
                    short = row[short].ifBlank { TransitNetwork.parseRouteShortName(routeId) },
                    long = row[long],
                    type = row[type].toIntOrNull() ?: 3,
                    color = row[color].ifBlank { "000000" },
                    text = row[text].ifBlank { "FFFFFF" },
                    sort = row[sort].toIntOrNull() ?: Int.MAX_VALUE,
                )
            }
        }
        raw.sortWith(compareBy({ it.sort }, { it.short }))
        val list = raw.map {
            Route(it.short, it.long, it.color.uppercase(), it.text.uppercase(), RouteMode.fromGtfs(it.type), it.sort, arrayOfNulls(2))
        }
        return Routes(list, raw.indices.associateBy { raw[it].id })
    }

    // ---------------------------------------------------------------- stops

    private class Stops(
        val places: List<RawPlace>,
        val quayNumber: IntArray,
        val quayParent: IntArray,
        val quayLat: DoubleArray,
        val quayLon: DoubleArray,
        val quayIndexById: Map<String, Int>,
    )

    private class RawPlace(val number: Int, val name: String, val lat: Double, val lon: Double, val wheelchair: Boolean)

    private fun readStops(): Stops {
        val places = ArrayList<RawPlace>()
        val placeIndexById = HashMap<String, Int>()
        class RawQuay(val id: String, val number: Int, val parent: String, val lat: Double, val lon: Double, val name: String)
        val quays = ArrayList<RawQuay>()
        csv("stops.txt") { r ->
            val id = r.requireColumn("stop_id")
            val name = r.requireColumn("stop_name")
            val lat = r.requireColumn("stop_lat")
            val lon = r.requireColumn("stop_lon")
            val type = r.column("location_type")
            val parent = r.column("parent_station")
            val wheelchair = r.column("wheelchair_boarding")
            r.forEachRow { row ->
                val stopId = row[id]
                val number = trailingNumber(stopId)
                when (row[type].ifBlank { "0" }) {
                    "1" -> {
                        placeIndexById[stopId] = places.size
                        places += RawPlace(number, row[name], row[lat].toDouble(), row[lon].toDouble(), row[wheelchair] == "1")
                    }
                    "0" -> quays += RawQuay(stopId, number, row[parent], row[lat].toDouble(), row[lon].toDouble(), row[name])
                    else -> Unit // entrances, nodes, boarding areas: not needed
                }
            }
        }
        // A quay without a known parent becomes its own StopPlace so its trips are never lost.
        val parentOf = IntArray(quays.size)
        quays.forEachIndexed { i, q ->
            parentOf[i] = placeIndexById[q.parent] ?: run {
                val idx = places.size
                places += RawPlace(q.number, q.name, q.lat, q.lon, false)
                placeIndexById[q.id] = idx
                idx
            }
        }
        return Stops(
            places = places,
            quayNumber = IntArray(quays.size) { quays[it].number },
            quayParent = parentOf,
            quayLat = DoubleArray(quays.size) { quays[it].lat },
            quayLon = DoubleArray(quays.size) { quays[it].lon },
            quayIndexById = quays.indices.associateBy { quays[it].id },
        )
    }

    // ---------------------------------------------------------------- calendar

    private class Calendar(val start: LocalDate, val dayCount: Int, val serviceIndex: Map<String, Int>, val bits: List<LongArray>)

    private fun readCalendar(): Calendar {
        class Weekly(val id: String, val days: BooleanArray, val start: LocalDate, val end: LocalDate)
        val weekly = ArrayList<Weekly>()
        csv("calendar.txt", optional = true) { r ->
            val id = r.requireColumn("service_id")
            val dayCols = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map { r.requireColumn(it) }
            val s = r.requireColumn("start_date")
            val e = r.requireColumn("end_date")
            r.forEachRow { row ->
                weekly += Weekly(row[id], BooleanArray(7) { row[dayCols[it]] == "1" }, parseDate(row[s]), parseDate(row[e]))
            }
        }
        class Exception(val id: String, val date: LocalDate, val added: Boolean)
        val exceptions = ArrayList<Exception>()
        csv("calendar_dates.txt", optional = true) { r ->
            val id = r.requireColumn("service_id")
            val d = r.requireColumn("date")
            val t = r.requireColumn("exception_type")
            r.forEachRow { row -> exceptions += Exception(row[id], parseDate(row[d]), row[t] == "1") }
        }
        val allDates = weekly.flatMap { listOf(it.start, it.end) } + exceptions.filter { it.added }.map { it.date }
        if (allDates.isEmpty()) throw GtfsFormatException("no calendar")
        val start = allDates.min()
        val end = allDates.max()
        val dayCount = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
        val words = (dayCount + 63) / 64
        val index = LinkedHashMap<String, Int>()
        val bits = ArrayList<LongArray>()
        fun bitsOf(id: String): LongArray = bits[index.getOrPut(id) { bits += LongArray(words); bits.size - 1 }]
        for (w in weekly) {
            val b = bitsOf(w.id)
            var d = w.start
            while (!d.isAfter(w.end)) {
                if (w.days[d.dayOfWeek.value - 1]) setBit(b, (d.toEpochDay() - start.toEpochDay()).toInt())
                d = d.plusDays(1)
            }
        }
        for (x in exceptions) {
            val b = bitsOf(x.id)
            val day = (x.date.toEpochDay() - start.toEpochDay()).toInt()
            if (day !in 0 until dayCount) continue
            if (x.added) setBit(b, day) else b[day ushr 6] = b[day ushr 6] and (1L shl (day and 63)).inv()
        }
        return Calendar(start, dayCount, index, bits)
    }

    // ---------------------------------------------------------------- trips

    private class Trips(
        val count: Int,
        val route: IntArray,
        val service: IntArray,
        val direction: IntArray,
        val headsign: IntArray,
        val shape: IntArray,
        val vj: IntArray,
        val headsigns: List<String>,
        val shapeIds: List<String>,
        val index: TripIndex,
    )

    private fun readTrips(routeIndex: Map<String, Int>, calendar: Calendar): Trips {
        val route = IntList(); val service = IntList(); val direction = IntList()
        val headsign = IntList(); val shape = IntList(); val vj = IntList()
        val headsigns = Interner(); val shapeIds = Interner()
        val index = TripIndex(calendar.serviceIndex)
        csv("trips.txt") { r ->
            val rid = r.requireColumn("route_id")
            val sid = r.requireColumn("service_id")
            val tid = r.requireColumn("trip_id")
            val hs = r.column("trip_headsign")
            val dir = r.column("direction_id")
            val sh = r.column("shape_id")
            r.forEachRow { row ->
                val ri = routeIndex[row[rid]] ?: return@forEachRow
                val si = calendar.serviceIndex[row[sid]] ?: return@forEachRow // service never runs
                val tripId = row[tid]
                index.put(tripId, route.size)
                route += ri; service += si
                direction += (row[dir].toIntOrNull() ?: 0).coerceIn(0, 1)
                headsign += headsigns.intern(row[hs])
                shape += if (sh < 0 || row[sh].isBlank()) -1 else shapeIds.intern(row[sh])
                vj += TransitNetwork.parseVehicleJourneyNumber(tripId) ?: -1
            }
        }
        return Trips(route.size, route.toArray(), service.toArray(), direction.toArray(), headsign.toArray(), shape.toArray(), vj.toArray(), headsigns.values, shapeIds.values, index)
    }

    // ---------------------------------------------------------------- stop_times

    private class UngroupedStopTimesException : Exception()

    private class Times(
        val patterns: List<PatternKey>,
        val patternShapeIds: IntArray,
        val profilesArr: List<IntArray>,
        val profilesDep: List<IntArray>,
        val uniquePattern: IntArray,
        val uniqueStart: IntArray,
        val uniqueProfile: IntArray,
        /** original trip -> unique trip (-1 when the trip had no stop times). */
        val tripUnique: IntArray,
    )

    private data class PatternKey(val route: Int, val direction: Int, val headsign: Int, val quays: IntArrayKey)

    private fun readStopTimes(stops: Stops, trips: Trips, grouped: Boolean): Times {
        val patterns = LinkedHashMap<PatternKey, Int>()
        val patternShape = IntList()
        val profiles = LinkedHashMap<IntArrayKey, Int>()
        val profilesArr = ArrayList<IntArray>(); val profilesDep = ArrayList<IntArray>()
        val unique = HashMap<Long, Int>() // (pattern, start, profile) packed
        val uPattern = IntList(); val uStart = IntList(); val uProfile = IntList()
        val tripUnique = IntArray(trips.count) { -1 }

        fun finalize(trip: Int, seq: IntList, quay: IntList, arr: IntList, dep: IntList) {
            val n = seq.size
            if (n < 2) return
            val order = (0 until n).sortedBy { seq[it] }
            val quays = IntArray(n) { quay[order[it]] }
            val start = dep[order[0]]
            val pa = IntArray(n) { arr[order[it]] - start }
            val pd = IntArray(n) { dep[order[it]] - start }
            val pKey = PatternKey(trips.route[trip], trips.direction[trip], trips.headsign[trip], IntArrayKey(quays))
            val p = patterns.getOrPut(pKey) { patternShape += trips.shape[trip]; patterns.size }
            val profKey = IntArrayKey(pa + pd)
            val prof = profiles.getOrPut(profKey) { profilesArr += pa; profilesDep += pd; profiles.size }
            val key = (p.toLong() shl 42) or (prof.toLong() shl 20) or (start.toLong() and 0xFFFFF)
            tripUnique[trip] = unique.getOrPut(key) {
                uPattern += p; uStart += start; uProfile += prof
                uPattern.size - 1
            }
        }

        val seq = IntList(); val quay = IntList(); val arr = IntList(); val dep = IntList()
        // Buffered (ungrouped) mode: per-trip row buffers.
        val buffers = if (grouped) null else HashMap<Int, Array<IntList>>()
        val done = BooleanArray(trips.count)
        var current = -1
        csv("stop_times.txt") { r ->
            val tid = r.requireColumn("trip_id")
            val at = r.requireColumn("arrival_time")
            val dt = r.requireColumn("departure_time")
            val sid = r.requireColumn("stop_id")
            val sq = r.requireColumn("stop_sequence")
            var lastTripId = ""
            var lastTrip = -1
            r.forEachRow { row ->
                val tripId = row[tid]
                val trip = if (tripId == lastTripId) lastTrip else trips.index.get(tripId).also { lastTripId = tripId; lastTrip = it }
                if (trip < 0) return@forEachRow
                val q = stops.quayIndexById[row[sid]] ?: return@forEachRow
                val a = parseTime(row[at]); val d = parseTime(row[dt])
                val dd = if (d < 0) a else d
                val aa = if (a < 0) dd else a
                if (aa < 0) return@forEachRow // untimed stop (none in the Naolib feed)
                if (buffers != null) {
                    val b = buffers.getOrPut(trip) { Array(4) { IntList() } }
                    b[0] += row[sq].toInt(); b[1] += q; b[2] += aa; b[3] += dd
                    return@forEachRow
                }
                if (trip != current) {
                    if (current >= 0) {
                        finalize(current, seq, quay, arr, dep)
                        done[current] = true
                    }
                    if (done[trip]) throw UngroupedStopTimesException()
                    current = trip
                    seq.clear(); quay.clear(); arr.clear(); dep.clear()
                }
                seq += row[sq].toInt(); quay += q; arr += aa; dep += dd
            }
        }
        if (buffers == null) {
            if (current >= 0) finalize(current, seq, quay, arr, dep)
        } else {
            for ((trip, b) in buffers) finalize(trip, b[0], b[1], b[2], b[3])
        }
        return Times(
            patterns = patterns.keys.toList(),
            patternShapeIds = patternShape.toArray(),
            profilesArr = profilesArr,
            profilesDep = profilesDep,
            uniquePattern = uPattern.toArray(),
            uniqueStart = uStart.toArray(),
            uniqueProfile = uProfile.toArray(),
            tripUnique = tripUnique,
        )
    }

    // ---------------------------------------------------------------- shapes

    private fun readShapes(wanted: IntArray, trips: Trips): Map<Int, DoubleArray> {
        // shape interner index -> points; only shapes some pattern uses.
        val wantedSet = wanted.filter { it >= 0 }.toSet()
        if (wantedSet.isEmpty()) return emptyMap()
        val idToIndex = trips.shapeIds.withIndex().filter { it.index in wantedSet }.associate { it.value to it.index }
        val points = HashMap<Int, ArrayList<DoubleArray>>() // index -> [seq, lat, lon]
        csv("shapes.txt", optional = true) { r ->
            val id = r.requireColumn("shape_id")
            val lat = r.requireColumn("shape_pt_lat")
            val lon = r.requireColumn("shape_pt_lon")
            val sq = r.requireColumn("shape_pt_sequence")
            r.forEachRow { row ->
                val idx = idToIndex[row[id]] ?: return@forEachRow
                points.getOrPut(idx) { ArrayList() } += doubleArrayOf(row[sq].toDouble(), row[lat].toDouble(), row[lon].toDouble())
            }
        }
        return points.mapValues { (_, pts) ->
            pts.sortBy { it[0] }
            DoubleArray(pts.size * 2) { i -> pts[i / 2][1 + i % 2] }
        }
    }

    // ---------------------------------------------------------------- assemble

    private fun assemble(
        routes: Routes,
        stops: Stops,
        calendar: Calendar,
        trips: Trips,
        times: Times,
        shapes: Map<Int, DoubleArray>,
    ): TransitNetwork {
        // Service sets: OR of the original services of every trip merged into each unique trip.
        val words = (calendar.dayCount + 63) / 64
        val uniqueCount = times.uniquePattern.size
        val uniqueBits = Array(uniqueCount) { LongArray(words) }
        val uniqueHeadsign = IntArray(uniqueCount) { -1 }
        for (t in 0 until trips.count) {
            val u = times.tripUnique[t]
            if (u < 0) continue
            val src = calendar.bits[trips.service[t]]
            val dst = uniqueBits[u]
            for (w in 0 until words) dst[w] = dst[w] or src[w]
            if (uniqueHeadsign[u] < 0) uniqueHeadsign[u] = trips.headsign[t]
        }
        val serviceSetIndex = LinkedHashMap<LongArrayKey, Int>()
        val tripSet = IntArray(uniqueCount) { u -> serviceSetIndex.getOrPut(LongArrayKey(uniqueBits[u])) { serviceSetIndex.size } }

        // Order unique trips by (pattern, start) - RAPTOR scans a pattern's trips in departure order.
        val order = (0 until uniqueCount).sortedWith(compareBy({ times.uniquePattern[it] }, { times.uniqueStart[it] }))
        val newIndex = IntArray(uniqueCount)
        order.forEachIndexed { newI, oldI -> newIndex[oldI] = newI }

        // Direction names: most common headsign per (route, direction), weighted by original trips.
        val headsignCounts = HashMap<Long, HashMap<Int, Int>>()
        for (t in 0 until trips.count) {
            if (times.tripUnique[t] < 0) continue
            val k = (trips.route[t].toLong() shl 1) or trips.direction[t].toLong()
            val m = headsignCounts.getOrPut(k) { HashMap() }
            m[trips.headsign[t]] = (m[trips.headsign[t]] ?: 0) + 1
        }
        val routeList = routes.list.mapIndexed { ri, r ->
            val names = arrayOfNulls<String>(2)
            for (dir in 0..1) {
                val best = headsignCounts[(ri.toLong() shl 1) or dir.toLong()]?.maxByOrNull { it.value }?.key
                names[dir] = best?.let { trips.headsigns[it] }?.takeIf { it.isNotBlank() }
            }
            Route(r.shortName, r.longName, r.color, r.textColor, r.mode, r.sortOrder, names)
        }

        // Shapes: keep only those used, re-indexed.
        val shapeIndex = LinkedHashMap<Int, Int>()
        val shapeList = ArrayList<DoubleArray>()
        val patterns = times.patterns.mapIndexed { p, key ->
            val sid = times.patternShapeIds[p]
            val shape = if (sid >= 0 && shapes.containsKey(sid)) {
                shapeIndex.getOrPut(sid) { shapeList += shapes.getValue(sid); shapeList.size - 1 }
            } else {
                -1
            }
            Pattern(key.route, key.direction, trips.headsigns[key.headsign], key.quays.values, shape)
        }

        // vj -> unique trip pairs (distinct), sorted by vj.
        val pairs = HashSet<Long>()
        for (t in 0 until trips.count) {
            val u = times.tripUnique[t]
            if (u < 0 || trips.vj[t] < 0) continue
            pairs += (trips.vj[t].toLong() shl 32) or newIndex[u].toLong()
        }
        val sortedPairs = pairs.toLongArray().also { it.sort() }

        // StopPlaces with their quays.
        val placeQuays = Array(stops.places.size) { IntList() }
        stops.quayParent.forEachIndexed { q, p -> placeQuays[p] += q }
        val stopPlaces = stops.places.mapIndexed { i, raw ->
            StopPlace(
                number = raw.number,
                name = raw.name,
                lat = raw.lat,
                lon = raw.lon,
                wheelchair = raw.wheelchair,
                quays = placeQuays[i].toArray(),
            )
        }

        val headsignArray = trips.headsigns.toTypedArray()
        return TransitNetwork(
            formatVersion = NetworkFormat.VERSION,
            feedId = feedId,
            validFrom = calendar.start,
            dayCount = calendar.dayCount,
            timezone = "Europe/Paris",
            routes = routeList,
            stopPlaces = stopPlaces,
            quayNumber = stops.quayNumber,
            quayStopPlace = stops.quayParent,
            quayLat = stops.quayLat,
            quayLon = stops.quayLon,
            patterns = patterns,
            tripPattern = IntArray(uniqueCount) { times.uniquePattern[order[it]] },
            tripStart = IntArray(uniqueCount) { times.uniqueStart[order[it]] },
            tripProfile = IntArray(uniqueCount) { times.uniqueProfile[order[it]] },
            tripServiceSet = IntArray(uniqueCount) { tripSet[order[it]] },
            tripHeadsign = IntArray(uniqueCount) { uniqueHeadsign[order[it]].coerceAtLeast(0) },
            headsigns = headsignArray,
            profileArrivals = times.profilesArr.toTypedArray(),
            profileDepartures = times.profilesDep.toTypedArray(),
            serviceSets = serviceSetIndex.keys.map { it.values }.toTypedArray(),
            vjNumbers = IntArray(sortedPairs.size) { (sortedPairs[it] ushr 32).toInt() },
            vjTrips = IntArray(sortedPairs.size) { sortedPairs[it].toInt() },
            shapes = shapeList.toTypedArray(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private inline fun csv(name: String, optional: Boolean = false, block: (CsvReader) -> Unit) {
        val stream = source.open(name) ?: if (optional) return else throw GtfsFormatException("missing $name")
        stream.use { block(CsvReader.of(it)) }
    }
}

/** Original trip id -> trip index. Long-keyed fast path for the Naolib id scheme, string fallback. */
private class TripIndex(private val serviceIndex: Map<String, Int>) {
    private val byKey = HashMap<Long, Int>()
    private val byString = HashMap<String, Int>()

    private fun key(tripId: String): Long? {
        val vj = TransitNetwork.parseVehicleJourneyNumber(tripId) ?: return null
        val dash = tripId.indexOf('-', TransitNetwork.VEHICLE_JOURNEY_PREFIX.length)
        if (dash < 0) return null
        val service = serviceIndex[SERVICE_PREFIX + tripId.substring(dash + 1)] ?: return null
        return (vj.toLong() shl 20) or service.toLong()
    }

    fun put(tripId: String, index: Int) {
        val k = key(tripId)
        if (k != null && !byKey.containsKey(k)) byKey[k] = index else byString[tripId] = index
    }

    fun get(tripId: String): Int {
        byString[tripId]?.let { return it }
        return key(tripId)?.let { byKey[it] } ?: -1
    }

    private companion object {
        const val SERVICE_PREFIX = "FR_NAOLIB:Timetable:"
    }
}

internal class IntList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    operator fun plusAssign(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

private class Interner {
    private val index = HashMap<String, Int>()
    val values = ArrayList<String>()
    fun intern(s: String): Int = index.getOrPut(s) { values += s; values.size - 1 }
}

internal class IntArrayKey(val values: IntArray) {
    private val hash = values.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is IntArrayKey && values.contentEquals(other.values)
}

private class LongArrayKey(val values: LongArray) {
    private val hash = values.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is LongArrayKey && values.contentEquals(other.values)
}

private fun setBit(bits: LongArray, day: Int) {
    bits[day ushr 6] = bits[day ushr 6] or (1L shl (day and 63))
}

/** `yyyyMMdd` -> date. */
internal fun parseDate(s: String): LocalDate =
    LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt())

/** `H:MM:SS` / `HH:MM:SS` (hours may exceed 24) -> seconds, or -1 when blank/malformed. */
internal fun parseTime(s: String): Int {
    if (s.isEmpty()) return -1
    var h = 0
    var i = 0
    while (i < s.length && s[i] != ':') {
        val c = s[i]
        if (c !in '0'..'9') return -1
        h = h * 10 + (c - '0'); i++
    }
    if (i + 6 > s.length) return -1
    val m = (s[i + 1] - '0') * 10 + (s[i + 2] - '0')
    val sec = (s[i + 4] - '0') * 10 + (s[i + 5] - '0')
    return h * 3600 + m * 60 + sec
}

/** Numeric suffix of a `FR_NAOLIB:Quay:123`-style id (hash fallback for non-numeric ids). */
private fun trailingNumber(id: String): Int =
    id.substringAfterLast(':').toIntOrNull() ?: (id.hashCode() and 0x7FFFFFFF)

package com.bustramnantes.transit.gtfs

import com.bustramnantes.transit.TransitNetwork
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * How a small, separately published GTFS is folded into the main (Naolib) one - see [appending].
 *
 * @property prefix namespace of the rewritten ids (`"BAC44"` -> `BAC44:Quay:9900003`, `BAC44:B1`…).
 * @property numberBase added to each stop's number: the quay and its StopPlace get number
 *   `numberBase + n`, inside the range reserved by [TransitNetwork.EXTERNAL_NUMBER_BASE] so they never
 *   clash with Naolib ids and the app can tell them apart (no SIRI for them).
 * @property longName rider-facing long name of a route from its feed `route_long_name`.
 * @property headsign rider-facing headsign from the feed's `trip_headsign`.
 * @property collisionPrefix put before a short name the main feed already uses ("Bac B2").
 */
class AppendedFeedSpec(
    val prefix: String,
    val numberBase: Int,
    val color: String,
    val textColor: String,
    val longName: (String) -> String = { it },
    val headsign: (String) -> String = { it },
    val collisionPrefix: String = prefix,
)

/**
 * This feed with [extra] appended: every file the converter reads is the main feed's file followed by
 * the extra feed's rows, re-keyed to the main file's columns (so [GtfsConverter] needs no change):
 *  - stops: each extra stop becomes a quay plus its own StopPlace (`location_type=1`), numbered
 *    [AppendedFeedSpec.numberBase] + its number;
 *  - routes, trips, shapes, services: ids prefixed;
 *  - `frequencies.txt` trips are **expanded** into one trip per departure (`start_time`, + headway,
 *    while < `end_time` - the GTFS reading; with `exact_times=0` the times are nominal);
 *  - the extra calendar is **clipped to the main feed's date range**, so a long-running extra feed
 *    never stretches the network's validity (the app picks the network valid longest).
 * Re-openable like any source (the converter may read stop_times twice).
 */
fun GtfsSource.appending(extra: GtfsSource, spec: AppendedFeedSpec): GtfsSource {
    val main = this
    val rows by lazy { ExtraRows.build(main, extra, spec) }
    return GtfsSource { name ->
        val extraRows = rows[name].orEmpty()
        val mainStream = main.open(name)
        when {
            extraRows.isEmpty() -> mainStream
            mainStream == null -> ByteArrayInputStream(csvText(extraRows.first().keys.toList(), extraRows).toByteArray())
            else -> {
                val header = main.open(name)!!.use(::readHeader)
                SequenceInputStream(mainStream, ByteArrayInputStream(("\n" + csvText(header, extraRows, withHeader = false)).toByteArray()))
            }
        }
    }
}

private fun readHeader(stream: InputStream): List<String> {
    val reader = CsvReader.of(stream)
    return reader.header.entries.sortedBy { it.value }.map { it.key }
}

private fun csvText(columns: List<String>, rows: List<Map<String, String>>, withHeader: Boolean = true): String {
    val sb = StringBuilder()
    fun quote(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    if (withHeader) sb.append(columns.joinToString(",", transform = ::quote)).append('\n')
    for (row in rows) sb.append(columns.joinToString(",") { quote(row[it].orEmpty()) }).append('\n')
    return sb.toString()
}

private object ExtraRows {
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun build(main: GtfsSource, extra: GtfsSource, spec: AppendedFeedSpec): Map<String, List<Map<String, String>>> {
        val (mainStart, mainEnd) = dateRange(main)
        val p = spec.prefix
        val out = HashMap<String, MutableList<Map<String, String>>>()
        fun add(file: String, row: Map<String, String>) { out.getOrPut(file) { ArrayList() } += row }

        // Stops -> quay + StopPlace.
        val quayIdOf = HashMap<String, String>()
        read(extra, "stops.txt").forEachIndexed { i, s ->
            if ((s["location_type"] ?: "").ifBlank { "0" } != "0") return@forEachIndexed
            val n = spec.numberBase + (s["stop_id"]!!.substringAfterLast(':').toIntOrNull() ?: (i + 1))
            val place = "$p:StopPlace:$n"
            val quay = "$p:Quay:$n"
            quayIdOf[s["stop_id"]!!] = quay
            val common = mapOf("stop_name" to s["stop_name"].orEmpty(), "stop_lat" to s["stop_lat"].orEmpty(), "stop_lon" to s["stop_lon"].orEmpty(), "wheelchair_boarding" to s["wheelchair_boarding"].orEmpty())
            add("stops.txt", common + mapOf("stop_id" to place, "location_type" to "1"))
            add("stops.txt", common + mapOf("stop_id" to quay, "location_type" to "0", "parent_station" to place))
        }

        // A short name the main feed already uses gets the "<collisionPrefix> " form, so line ids
        // (`FR_NAOLIB:Line:<short>`) never collide.
        val mainShortNames = read(main, "routes.txt").map { it["route_short_name"].orEmpty() }.toSet()
        read(extra, "routes.txt").forEach { r ->
            val short = r["route_short_name"].orEmpty().let { if (it in mainShortNames) "${spec.collisionPrefix} $it" else it }
            add(
                "routes.txt",
                mapOf(
                    "route_id" to "$p:${r["route_id"]}",
                    "agency_id" to "$p:${r["agency_id"].orEmpty()}",
                    "route_short_name" to short,
                    "route_long_name" to spec.longName(r["route_long_name"].orEmpty()),
                    "route_type" to r["route_type"].orEmpty(),
                    "route_color" to r["route_color"].orEmpty().ifBlank { spec.color },
                    "route_text_color" to r["route_text_color"].orEmpty().ifBlank { spec.textColor },
                    "route_sort_order" to "100000",
                ),
            )
        }

        // Services, clipped to the main feed's range.
        read(extra, "calendar.txt").forEach { c ->
            val start = maxOf(LocalDate.parse(c["start_date"], DATE), mainStart)
            val end = minOf(LocalDate.parse(c["end_date"], DATE), mainEnd)
            if (start.isAfter(end)) return@forEach
            add("calendar.txt", c + mapOf("service_id" to "$p:${c["service_id"]}", "start_date" to start.format(DATE), "end_date" to end.format(DATE)))
        }
        read(extra, "calendar_dates.txt").forEach { c ->
            val date = LocalDate.parse(c["date"], DATE)
            if (date.isBefore(mainStart) || date.isAfter(mainEnd)) return@forEach
            add("calendar_dates.txt", c + mapOf("service_id" to "$p:${c["service_id"]}"))
        }

        read(extra, "shapes.txt").forEach { s -> add("shapes.txt", s + mapOf("shape_id" to "$p:${s["shape_id"]}")) }

        // Trips (frequency-based ones expanded) and their stop times.
        val stopTimes = read(extra, "stop_times.txt").groupBy { it["trip_id"]!! }
            .mapValues { (_, v) -> v.sortedBy { it["stop_sequence"]!!.toInt() } }
        val frequencies = read(extra, "frequencies.txt").groupBy { it["trip_id"]!! }
        read(extra, "trips.txt").forEach { t ->
            val tripId = t["trip_id"]!!
            val times = stopTimes[tripId] ?: return@forEach
            val first = parseTime(times.first()["departure_time"].orEmpty().ifBlank { times.first()["arrival_time"].orEmpty() })
            val starts: List<Int> = frequencies[tripId]?.flatMap { f ->
                val s = parseTime(f["start_time"]!!); val e = parseTime(f["end_time"]!!); val h = f["headway_secs"]!!.toInt()
                generateSequence(s) { it + h }.takeWhile { it < e }.toList()
            } ?: listOf(first)
            for (start in starts) {
                val id = if (frequencies.containsKey(tripId)) "$p:$tripId@${hhmmss(start)}" else "$p:$tripId"
                add(
                    "trips.txt",
                    mapOf(
                        "route_id" to "$p:${t["route_id"]}",
                        "service_id" to "$p:${t["service_id"]}",
                        "trip_id" to id,
                        "trip_headsign" to spec.headsign(t["trip_headsign"].orEmpty()),
                        "direction_id" to t["direction_id"].orEmpty(),
                        "shape_id" to t["shape_id"].orEmpty().let { if (it.isBlank()) "" else "$p:$it" },
                    ),
                )
                for (st in times) {
                    val arr = parseTime(st["arrival_time"].orEmpty().ifBlank { st["departure_time"].orEmpty() }) - first + start
                    val dep = parseTime(st["departure_time"].orEmpty().ifBlank { st["arrival_time"].orEmpty() }) - first + start
                    add(
                        "stop_times.txt",
                        mapOf(
                            "trip_id" to id,
                            "arrival_time" to hhmmss(arr),
                            "departure_time" to hhmmss(dep),
                            "stop_id" to (quayIdOf[st["stop_id"]] ?: continue),
                            "stop_sequence" to st["stop_sequence"].orEmpty(),
                        ),
                    )
                }
            }
        }
        return out
    }

    /** First and last service date of [source] (as the converter computes it). */
    private fun dateRange(source: GtfsSource): Pair<LocalDate, LocalDate> {
        val dates = ArrayList<LocalDate>()
        read(source, "calendar.txt").forEach { dates += LocalDate.parse(it["start_date"], DATE); dates += LocalDate.parse(it["end_date"], DATE) }
        read(source, "calendar_dates.txt").forEach { if (it["exception_type"] == "1") dates += LocalDate.parse(it["date"], DATE) }
        if (dates.isEmpty()) throw GtfsFormatException("main feed has no calendar")
        return dates.min() to dates.max()
    }

    private fun read(source: GtfsSource, name: String): List<Map<String, String>> {
        val stream = source.open(name) ?: return emptyList()
        return stream.use {
            val reader = CsvReader.of(it)
            val columns = reader.header.entries.map { e -> e.key to e.value }
            val rows = ArrayList<Map<String, String>>()
            reader.forEachRow { row -> rows += columns.associate { (k, i) -> k to row[i] } }
            rows
        }
    }

    private fun hhmmss(t: Int): String = "%02d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
}

package com.bustramnantes.transit

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Read-side queries over a [TransitNetwork]: what the stop sheet, the timetable screen and the
 * departure board need from the static timetable. All pure; times in epoch seconds.
 */

/** One scheduled passage of a trip at a quay. */
data class ScheduledCall(
    val trip: Int,
    val quay: Int,
    /** Position of [quay] in the trip's pattern. */
    val position: Int,
    val serviceDay: LocalDate,
    val route: Int,
    val directionId: Int,
    val headsign: String,
    val arrivalEpochSecond: Long,
    val departureEpochSecond: Long,
    /** The trip ends at this quay (riders can't board; the official board omits these). */
    val isTerminating: Boolean,
)

/** A line serving a stop, with the directions that can actually be boarded there. */
data class ServedRoute(val route: Int, val directions: List<Int>)

/** One row of a day's timetable. */
data class TimetableRow(
    val time: LocalTime,
    /** Source time was ≥ 24:00 (runs the morning after the service day). */
    val nextDay: Boolean,
    val headsign: String,
)

/** Epoch second of the service day's reference midnight (GTFS: noon minus 12 h, DST-proof). */
fun TransitNetwork.serviceDayStart(date: LocalDate): Long =
    date.atTime(12, 0).atZone(ZoneId.of(timezone)).toEpochSecond() - 12 * 3600

/**
 * The lines and directions boardable at [stopPlace]. A direction where the stop is the **last** quay
 * of every pattern is terminus-only (you can only alight) and is left out - the GTFS-based answer to
 * "don't show a 'towards here' block at the terminus".
 */
fun TransitNetwork.servedRoutes(stopPlace: Int): List<ServedRoute> {
    val dirs = sortedMapOf<Int, java.util.TreeSet<Int>>()
    for (q in stopPlaces[stopPlace].quays) {
        val pairs = quayPatterns[q]
        var i = 0
        while (i < pairs.size) {
            val pattern = patterns[pairs[i]]
            val pos = pairs[i + 1]
            if (pos < pattern.quays.size - 1) dirs.getOrPut(pattern.route) { java.util.TreeSet() } += pattern.directionId
            i += 2
        }
    }
    return dirs.map { (route, d) -> ServedRoute(route, d.toList()) }
}

/** Every route with a pattern through the stop, boardable or not (map badges / search badges). */
fun TransitNetwork.linkedRoutes(stopPlace: Int): List<Int> {
    val set = sortedSetOf<Int>()
    for (q in stopPlaces[stopPlace].quays) {
        val pairs = quayPatterns[q]
        var i = 0
        while (i < pairs.size) { set += patterns[pairs[i]].route; i += 2 }
    }
    return set.toList()
}

/**
 * Scheduled calls at [quays] departing in `[fromEpochSecond, untilEpochSecond]`, sorted by departure.
 * Looks at the service days that can contain such times (the day before, for after-midnight runs).
 */
fun TransitNetwork.scheduledCalls(
    quays: IntArray,
    fromEpochSecond: Long,
    untilEpochSecond: Long,
    includeTerminating: Boolean = false,
): List<ScheduledCall> {
    val zone = ZoneId.of(timezone)
    val firstDay = java.time.Instant.ofEpochSecond(fromEpochSecond).atZone(zone).toLocalDate().minusDays(1)
    val lastDay = java.time.Instant.ofEpochSecond(untilEpochSecond).atZone(zone).toLocalDate()
    val result = ArrayList<ScheduledCall>()
    var day = firstDay
    while (!day.isAfter(lastDay)) {
        val dayIndex = dayIndex(day)
        if (dayIndex >= 0) {
            val base = serviceDayStart(day)
            for (q in quays) {
                val pairs = quayPatterns[q]
                var i = 0
                while (i < pairs.size) {
                    val p = pairs[i]
                    val pos = pairs[i + 1]
                    i += 2
                    val pattern = patterns[p]
                    val terminating = pos == pattern.quays.size - 1
                    if (terminating && !includeTerminating) continue
                    for (t in patternTripStart[p] until patternTripStart[p + 1]) {
                        val dep = base + departureAt(t, pos)
                        if (dep < fromEpochSecond || dep > untilEpochSecond) continue
                        if (!isTripActive(t, dayIndex)) continue
                        result += ScheduledCall(
                            trip = t, quay = q, position = pos, serviceDay = day,
                            route = pattern.route, directionId = pattern.directionId,
                            headsign = headsigns[tripHeadsign[t]],
                            arrivalEpochSecond = base + arrivalAt(t, pos), departureEpochSecond = dep,
                            isTerminating = terminating,
                        )
                    }
                }
            }
        }
        day = day.plusDays(1)
    }
    result.sortBy { it.departureEpochSecond }
    return result
}

/**
 * The full timetable of one line+direction at a stop for a service [date] (the "Horaires théoriques"
 * screen), chronological, after-midnight runs last with [TimetableRow.nextDay]. Empty when the date is
 * outside the feed or nothing runs - the screen shows its "no schedule" state.
 */
fun TransitNetwork.timetable(stopPlace: Int, route: Int, directionId: Int, date: LocalDate): List<TimetableRow> {
    val dayIndex = dayIndex(date)
    if (dayIndex < 0) return emptyList()
    val seconds = ArrayList<Pair<Int, String>>()
    for (q in stopPlaces[stopPlace].quays) {
        val pairs = quayPatterns[q]
        var i = 0
        while (i < pairs.size) {
            val p = pairs[i]
            val pos = pairs[i + 1]
            i += 2
            val pattern = patterns[p]
            if (pattern.route != route || pattern.directionId != directionId) continue
            if (pos == pattern.quays.size - 1) continue
            for (t in patternTripStart[p] until patternTripStart[p + 1]) {
                if (isTripActive(t, dayIndex)) seconds += departureAt(t, pos) to headsigns[tripHeadsign[t]]
            }
        }
    }
    seconds.sortBy { it.first }
    return seconds.map { (s, headsign) ->
        TimetableRow(LocalTime.ofSecondOfDay((s % 86_400).toLong()), s >= 86_400, headsign)
    }
}

/** Great-circle distance in metres. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
}

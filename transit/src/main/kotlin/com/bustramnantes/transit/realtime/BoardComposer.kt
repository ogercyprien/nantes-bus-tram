package com.bustramnantes.transit.realtime

import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.scheduledCalls
import com.bustramnantes.transit.serviceDayStart
import java.time.Instant
import java.time.ZoneId

/** Where a board entry's time comes from. */
enum class EntrySource { SIRI, GTFS_RT, TIMETABLE }

/**
 * One departure of a composed stop board, in network terms ([route] index, GTFS [directionId]).
 * [route]/[directionId] are null only for a real-time visit whose line/trip the installed GTFS doesn't
 * know (a newer publication) - it is still shown, from what SIRI itself says.
 */
data class BoardEntry(
    val route: Int?,
    val lineShortName: String,
    val directionId: Int?,
    /** Per-trip destination (SIRI `DestinationName` or the trip's GTFS headsign). */
    val destination: String,
    val departureEpochSecond: Long,
    /** Timetabled time, when known. */
    val scheduledEpochSecond: Long?,
    val isRealtime: Boolean,
    /** GTFS `trip_id` of the run (from SIRI) or the unique trip key `"<trip>@<service day>"`. */
    val tripKey: String?,
    val source: EntrySource,
)

/**
 * Composes a stop's departure board from the static timetable and one real-time source - the
 * replacement for the official app's board (`plan-app /api/stop/logical/{id}`), which Okina SIRI
 * StopMonitoring reproduces to the second (SOURCES.md §5.2).
 *
 * **SIRI mode** ([composeWithSiri]):
 *  1. real-time part: the SIRI visits at the stop's quays, mapped to GTFS trips; visits whose
 *     `DestinationRef` is one of the stop's own quays are dropped (the trip ends here - the official
 *     board omits these, and riders can't board them); time = expected departure (else arrival);
 *  2. timetable part: scheduled GTFS calls in `[now, now + horizon]` whose trip SIRI didn't list;
 *  3. real-time flag: **listed by SIRI and ≤ [realtimeHorizonSeconds] ahead** - SIRI field values
 *     can't reproduce the official `is_rt` flag (809 of 1,505 board-RT entries had Expected == Aimed
 *     on whole minutes, exactly like timetable ones), but "trip present in SIRI ∧ ≤ 50 min" agreed
 *     with it on 93.8 % of departures and 97-99 % of those within 20 min (Sunday study).
 *
 * **GTFS-RT mode** ([composeWithTripUpdates]): the provisional board shown while the SIRI budget is
 * spent - the timetable with TripUpdates predictions applied where the (incomplete) feed has them.
 */
class BoardComposer(
    private val network: TransitNetwork,
    private val horizonSeconds: Long = 2 * 3600,
    private val realtimeHorizonSeconds: Long = DEFAULT_REALTIME_HORIZON_SECONDS,
) {
    private val zone = ZoneId.of(network.timezone)

    fun composeWithSiri(quays: IntArray, visits: List<SiriVisit>, nowEpochSecond: Long): List<BoardEntry> {
        val quayIds = quays.map { network.quayId(it) }.toSet()
        val seen = HashSet<String>()
        val entries = ArrayList<BoardEntry>()
        for (v in visits) {
            if (v.monitoringRef !in quayIds) continue
            val time = v.bestTime ?: continue
            val trip = v.vehicleJourneyRef?.let { resolve(it, v.aimedTime ?: time) }
            val key = trip?.let { tripKey(it.first, it.second) }
            // A trip SIRI lists is never re-added from the timetable, even when it is dropped below.
            if (key != null) seen += key
            if (v.destinationRef != null && v.destinationRef in quayIds) continue
            if (time < nowEpochSecond - DEPARTED_GRACE_SECONDS) continue
            val pattern = trip?.let { network.patterns[network.tripPattern[it.first]] }
            val route = pattern?.route ?: network.routeIndex(v.lineShortName)
            entries += BoardEntry(
                route = route,
                lineShortName = route?.let { network.routes[it].shortName } ?: v.lineShortName,
                directionId = pattern?.directionId ?: directionFromSiri(v.directionName),
                destination = v.destinationName ?: trip?.let { network.headsigns[network.tripHeadsign[it.first]] }.orEmpty(),
                departureEpochSecond = time,
                scheduledEpochSecond = v.aimedTime,
                isRealtime = time - nowEpochSecond <= realtimeHorizonSeconds,
                tripKey = key ?: v.vehicleJourneyRef,
                source = EntrySource.SIRI,
            )
        }
        for (call in network.scheduledCalls(quays, nowEpochSecond, nowEpochSecond + horizonSeconds)) {
            val key = tripKey(call.trip, call.serviceDay)
            if (key in seen) continue
            entries += timetableEntry(call.route, call.directionId, call.headsign, call.departureEpochSecond, key)
        }
        entries.sortBy { it.departureEpochSecond }
        return entries
    }

    /**
     * The timetable with GTFS-RT predictions overlaid: a scheduled call whose trip has a TripUpdate
     * takes the predicted time at that quay (or the delay of the last updated stop before it, GTFS-RT
     * delay propagation) and counts as real-time within the horizon; the rest stay theoretical.
     */
    fun composeWithTripUpdates(quays: IntArray, updates: Map<String, TripUpdate>, nowEpochSecond: Long): List<BoardEntry> {
        // Index updates by (unique trip, service day): the feed references original GTFS trip ids.
        val byKey = HashMap<String, TripUpdate>()
        for ((tripId, update) in updates) {
            val firstTime = update.stopTimes.firstNotNullOfOrNull { it.departureTime ?: it.arrivalTime } ?: nowEpochSecond
            val resolved = resolve(tripId, firstTime) ?: continue
            byKey[tripKey(resolved.first, resolved.second)] = update
        }
        val entries = ArrayList<BoardEntry>()
        // Look back a little: a delayed trip scheduled just before now may still be ahead.
        val calls = network.scheduledCalls(quays, nowEpochSecond - LATE_LOOKBACK_SECONDS, nowEpochSecond + horizonSeconds)
        for (call in calls) {
            val key = tripKey(call.trip, call.serviceDay)
            val update = byKey[key]
            val predicted = update?.let { predictedTime(it, call.trip, call.position, call.departureEpochSecond) }
            val time = predicted ?: call.departureEpochSecond
            if (time < nowEpochSecond - DEPARTED_GRACE_SECONDS) continue
            entries += if (predicted != null) {
                BoardEntry(
                    route = call.route,
                    lineShortName = network.routes[call.route].shortName,
                    directionId = call.directionId,
                    destination = call.headsign,
                    departureEpochSecond = time,
                    scheduledEpochSecond = call.departureEpochSecond,
                    isRealtime = time - nowEpochSecond <= realtimeHorizonSeconds,
                    tripKey = key,
                    source = EntrySource.GTFS_RT,
                )
            } else {
                timetableEntry(call.route, call.directionId, call.headsign, call.departureEpochSecond, key)
            }
        }
        entries.sortBy { it.departureEpochSecond }
        return entries.filter { it.departureEpochSecond <= nowEpochSecond + horizonSeconds }
    }

    /** Timetable only (no real-time source answered). */
    fun composeTimetable(quays: IntArray, nowEpochSecond: Long): List<BoardEntry> =
        network.scheduledCalls(quays, nowEpochSecond, nowEpochSecond + horizonSeconds).map { call ->
            timetableEntry(call.route, call.directionId, call.headsign, call.departureEpochSecond, tripKey(call.trip, call.serviceDay))
        }

    private fun timetableEntry(route: Int, dir: Int, headsign: String, time: Long, key: String) = BoardEntry(
        route = route,
        lineShortName = network.routes[route].shortName,
        directionId = dir,
        destination = headsign,
        departureEpochSecond = time,
        scheduledEpochSecond = time,
        isRealtime = false,
        tripKey = key,
        source = EntrySource.TIMETABLE,
    )

    private fun predictedTime(update: TripUpdate, trip: Int, position: Int, scheduled: Long): Long? {
        val pattern = network.patterns[network.tripPattern[trip]]
        var best: StopTimePrediction? = null
        var bestPos = -1
        for (st in update.stopTimes) {
            val quay = st.stopId?.let { network.quayIndex(it) } ?: continue
            val pos = pattern.quays.indexOf(quay)
            if (pos < 0 || pos > position) continue
            if (pos > bestPos) { best = st; bestPos = pos }
        }
        val st = best ?: return null
        if (bestPos == position) {
            st.departureTime?.let { return it }
            st.arrivalTime?.let { return it }
        }
        val delay = st.departureDelay ?: st.arrivalDelay ?: return null
        return scheduled + delay
    }

    /**
     * (unique trip, service day) for an original trip id running around [aroundEpochSecond].
     *
     * The calendar date of that instant is not enough: a run timetabled past midnight (C2 at 24:20 =
     * 00:20) belongs to the **previous** service day, yet a weekday vehicle journey is usually active on
     * the next date too - picking that date gave the SIRI visit the key `trip@tomorrow` while the
     * timetable call carried `trip@today`, and the same bus showed twice (real time + theoretical).
     * So every candidate day (the date and the day before) is tried and the one whose run actually
     * spans that instant (closest, for a late/early vehicle) wins.
     */
    private fun resolve(tripId: String, aroundEpochSecond: Long): Pair<Int, java.time.LocalDate>? {
        val date = Instant.ofEpochSecond(aroundEpochSecond).atZone(zone).toLocalDate()
        var best: Pair<Int, java.time.LocalDate>? = null
        var bestDistance = Long.MAX_VALUE
        for (day in listOf(date, date.minusDays(1))) {
            val trip = network.resolveTripId(tripId, day) ?: continue
            val base = network.serviceDayStart(day)
            val start = base + network.departureAt(trip, 0)
            val end = base + network.arrivalAt(trip, network.patterns[network.tripPattern[trip]].quays.size - 1)
            val distance = when {
                aroundEpochSecond < start -> start - aroundEpochSecond
                aroundEpochSecond > end -> aroundEpochSecond - end
                else -> 0L
            }
            if (distance < bestDistance) { best = trip to day; bestDistance = distance }
        }
        return best
    }

    companion object {
        /** "Present in SIRI ∧ ≤ this ahead" = real-time (provisional; re-checked on weekday data). */
        const val DEFAULT_REALTIME_HORIZON_SECONDS = 50 * 60L
        private const val DEPARTED_GRACE_SECONDS = 30L
        private const val LATE_LOOKBACK_SECONDS = 30 * 60L

        fun tripKey(trip: Int, serviceDay: java.time.LocalDate): String = "$trip@$serviceDay"

        /** SIRI `DirectionName`: `A` (aller) = GTFS direction 0, `R` (retour) = 1. */
        fun directionFromSiri(name: String?): Int? = when (name) {
            "A" -> 0
            "R" -> 1
            else -> null
        }
    }
}

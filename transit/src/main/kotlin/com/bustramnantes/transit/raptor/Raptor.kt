package com.bustramnantes.transit.raptor

import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.haversineMeters
import com.bustramnantes.transit.serviceDayStart
import com.bustramnantes.transit.walk.Footpaths
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Walking model: [walkedSeconds] for a distance actually walked (a street path on the OSM walking
 * graph, or a precomputed [com.bustramnantes.transit.walk.Footpaths] entry), [seconds] for a crow-fly
 * distance (× [detourFactor], used where the graph doesn't reach), plus [boardSlackSeconds] to board
 * any vehicle (after the access walk, a walking transfer or a same-quay change). Calibrated against the official planner (live comparison): it walks at
 * `walk_speed=4` (4 km/h) along streets and keeps a minute after alighting and before boarding; a
 * faster/0-margin model proposed connections it never would (median 8 min earlier on 40 random trips).
 */
data class WalkModel(
    /** 4 km/h. */
    val speedMetersPerSecond: Double = 4.0 / 3.6,
    val detourFactor: Double = 1.3,
    val boardSlackSeconds: Int = 60,
) {
    fun seconds(meters: Double): Int = ceil(meters * detourFactor / speedMetersPerSecond).toInt()
    fun walkedSeconds(meters: Double): Int = ceil(meters / speedMetersPerSecond).toInt()
    fun walkMeters(meters: Double): Int = (meters * detourFactor).toInt()
}

/** A quay reachable from the origin (or the destination) on foot in [walkSeconds]. */
data class AccessPoint(val quay: Int, val walkSeconds: Int, val walkMeters: Int = 0)

/** A trip on a given service day - what SIRI calls a dated vehicle journey. */
data class TripInstance(val trip: Int, val serviceDay: LocalDate)

sealed interface RaptorLeg {
    val fromEpochSecond: Long
    val toEpochSecond: Long

    /** Walk from the origin point to [quay]. */
    data class Access(val quay: Int, override val fromEpochSecond: Long, override val toEpochSecond: Long, val meters: Int) : RaptorLeg

    data class Ride(
        val trip: TripInstance,
        val pattern: Int,
        val boardPosition: Int,
        val alightPosition: Int,
        override val fromEpochSecond: Long,
        override val toEpochSecond: Long,
    ) : RaptorLeg

    /** Walk between two quays. */
    data class Transfer(val fromQuay: Int, val toQuay: Int, override val fromEpochSecond: Long, override val toEpochSecond: Long, val meters: Int) : RaptorLeg

    /** Walk from [quay] to the destination point. */
    data class Egress(val quay: Int, override val fromEpochSecond: Long, override val toEpochSecond: Long, val meters: Int) : RaptorLeg
}

data class RaptorJourney(val legs: List<RaptorLeg>) {
    val departureEpochSecond: Long get() = legs.first().fromEpochSecond
    val arrivalEpochSecond: Long get() = legs.last().toEpochSecond
    val rides: List<RaptorLeg.Ride> get() = legs.filterIsInstance<RaptorLeg.Ride>()
    val transfers: Int get() = (rides.size - 1).coerceAtLeast(0)
    val walkSeconds: Long get() = legs.filter { it !is RaptorLeg.Ride }.sumOf { it.toEpochSecond - it.fromEpochSecond }
}

/**
 * RAPTOR (Delling, Pajor, Werneck 2012) over the static timetable, for the on-device itinerary planner.
 *
 *  - [departAfter]: earliest arrival from an instant, repeated ("range") to return several options -
 *    each next search starts a minute after the previous best option's first departure.
 *  - [arriveBy]: latest departure for a given arrival - the same forward engine run on a
 *    **time-reversed** timetable (patterns reversed, times negated), so both modes share one scan.
 *  - Up to [MAX_RIDES] rides (4 transfers). Transfers are the network's precomputed street walks
 *    between quays ([TransitNetwork.footpaths], else crow-fly × 1.3 between quays ≤ 500 m apart);
 *    every boarding needs [WalkModel.boardSlackSeconds] of margin.
 *  - Service days: a query on date D scans the trips of D−1 (after-midnight runs), D and D+1.
 *  - [excluded] trips are never boarded - how the phantom-bus avoidance bans a vehicle that won't come.
 *
 * Each search returns the Pareto set over (arrival, rides): a journey with more rides is kept only if
 * it arrives strictly earlier (or, arrive-by, leaves strictly later).
 */
class RaptorRouter(private val network: TransitNetwork, val walk: WalkModel = WalkModel()) {

    /** The quay-to-quay walks in use: the network's street footpaths, else crow-fly. */
    val footpaths: Footpaths = network.footpaths ?: Footpaths.crowFly(network)

    /** Per quay: (neighbour quay, walk seconds, walk metres) triples, flattened. */
    private val transfers: Array<IntArray> = buildTransfers()

    private val dayCache = HashMap<Pair<LocalDate, Boolean>, DayTimetable>()

    /** Quays within [radiusMeters] of a point, crow-fly costed - access/egress where the walking graph doesn't reach. */
    fun quaysNear(lat: Double, lon: Double, radiusMeters: Double): List<AccessPoint> =
        (0 until network.quayCount).mapNotNull { q ->
            val d = haversineMeters(lat, lon, network.quayLat[q], network.quayLon[q])
            if (d <= radiusMeters) AccessPoint(q, walk.seconds(d), walk.walkMeters(d)) else null
        }

    fun walkSecondsBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int =
        walk.seconds(haversineMeters(lat1, lon1, lat2, lon2))

    /**
     * Up to [maxJourneys] options leaving at/after [departAfterEpochSecond], earliest arrival first
     * within each departure. Searches at most [windowSeconds] past the requested time.
     */
    fun departAfter(
        access: List<AccessPoint>,
        egress: List<AccessPoint>,
        departAfterEpochSecond: Long,
        maxJourneys: Int = 5,
        windowSeconds: Long = 2 * 3600,
        excluded: Set<TripInstance> = emptySet(),
    ): List<RaptorJourney> = range(access, egress, departAfterEpochSecond, maxJourneys, windowSeconds, excluded, reverse = false)

    /** Up to [maxJourneys] options arriving at/before [arriveByEpochSecond], latest departure first. */
    fun arriveBy(
        access: List<AccessPoint>,
        egress: List<AccessPoint>,
        arriveByEpochSecond: Long,
        maxJourneys: Int = 5,
        windowSeconds: Long = 2 * 3600,
        excluded: Set<TripInstance> = emptySet(),
    ): List<RaptorJourney> = range(egress, access, -arriveByEpochSecond, maxJourneys, windowSeconds, excluded, reverse = true)

    private fun range(
        from: List<AccessPoint>,
        to: List<AccessPoint>,
        start: Long,
        maxJourneys: Int,
        windowSeconds: Long,
        excluded: Set<TripInstance>,
        reverse: Boolean,
    ): List<RaptorJourney> {
        if (from.isEmpty() || to.isEmpty()) return emptyList()
        val found = LinkedHashMap<List<Any>, RaptorJourney>()
        var t = start
        while (found.size < maxJourneys && t <= start + windowSeconds) {
            val batch = search(from, to, t, excluded, reverse)
            if (batch.isEmpty()) break
            // In search space every journey "starts" at or after t; keep those inside the window.
            batch.filter { (if (reverse) -it.arrivalEpochSecond else it.departureEpochSecond) <= start + windowSeconds }
                .forEach { j -> found.putIfAbsent(signature(j), j) }
            // Next search starts a minute after this batch's earliest departure (latest arrival,
            // arrive-by) - in search space both are "the smallest start", hence one formula.
            val next = batch.minOf { if (reverse) -it.arrivalEpochSecond else it.departureEpochSecond } + 60
            t = maxOf(t + 60, next)
        }
        val list = found.values.toList()
        return if (reverse) {
            list.sortedWith(compareByDescending<RaptorJourney> { it.departureEpochSecond }.thenBy { it.transfers })
        } else {
            list.sortedWith(compareBy<RaptorJourney> { it.departureEpochSecond }.thenBy { it.arrivalEpochSecond })
        }
    }

    private fun signature(j: RaptorJourney): List<Any> =
        j.rides.map { it.trip to it.boardPosition } + listOf(j.arrivalEpochSecond, j.departureEpochSecond)

    // ------------------------------------------------------------------ core search

    /**
     * One RAPTOR run from [startTime] (epoch seconds; negated in reverse mode). Returns journeys
     * expressed in real (un-reversed) time.
     */
    private fun search(
        from: List<AccessPoint>,
        to: List<AccessPoint>,
        startTime: Long,
        excluded: Set<TripInstance>,
        reverse: Boolean,
    ): List<RaptorJourney> {
        val zone = ZoneId.of(network.timezone)
        val realStart = if (reverse) -startTime else startTime
        val date = java.time.Instant.ofEpochSecond(realStart).atZone(zone).toLocalDate()
        val day = dayCache.getOrPut(date to reverse) { DayTimetable.build(network, date, reverse) }
        if (dayCache.size > 6) dayCache.keys.filter { it.first != date }.take(dayCache.size - 6).forEach { dayCache.remove(it) }
        val t0 = (startTime - day.originEpoch).toInt()
        val excludedInstances = excluded.mapNotNull { day.instanceOf(it) }.toHashSet()

        val nq = network.quayCount
        val rounds = MAX_RIDES + 1
        val best = IntArray(nq) { INF }
        val tau = Array(rounds) { IntArray(nq) { INF } }
        val tauRide = Array(rounds) { IntArray(nq) { INF } }
        // Labels: ride labels (instance, board position, alight position, pattern) and transfer labels.
        val rideInstance = Array(rounds) { IntArray(nq) { -1 } }
        val rideBoardPos = Array(rounds) { IntArray(nq) }
        val rideAlightPos = Array(rounds) { IntArray(nq) }
        val ridePattern = Array(rounds) { IntArray(nq) }
        val viaTransfer = Array(rounds) { BooleanArray(nq) }
        val transferFrom = Array(rounds) { IntArray(nq) { -1 } }
        val transferSecs = Array(rounds) { IntArray(nq) }
        val transferMeters = Array(rounds) { IntArray(nq) }
        val accessSecs = IntArray(nq) { -1 }
        val accessMeters = IntArray(nq)

        val egressSecs = IntArray(nq) { -1 }
        val egressMeters = IntArray(nq)
        for (e in to) if (egressSecs[e.quay] < 0 || e.walkSeconds < egressSecs[e.quay]) {
            egressSecs[e.quay] = e.walkSeconds; egressMeters[e.quay] = e.walkMeters
        }

        var marked = BooleanArray(nq)
        for (a in from) {
            val t = t0 + a.walkSeconds
            if (t < tau[0][a.quay]) {
                tau[0][a.quay] = t; best[a.quay] = t; marked[a.quay] = true
                accessSecs[a.quay] = a.walkSeconds; accessMeters[a.quay] = a.walkMeters
            }
        }

        var targetBest = INF
        val results = ArrayList<Pair<Int, Int>>() // (round, egress quay)

        for (k in 1 until rounds) {
            // Patterns to scan, from their earliest marked position.
            val startPos = IntArray(day.patternCount) { Int.MAX_VALUE }
            var any = false
            for (q in 0 until nq) {
                if (!marked[q]) continue
                val pairs = day.quayPatterns[q]
                var i = 0
                while (i < pairs.size) {
                    val p = pairs[i]; val pos = pairs[i + 1]
                    if (pos < startPos[p]) { startPos[p] = pos; any = true }
                    i += 2
                }
            }
            if (!any) break
            val newlyMarked = BooleanArray(nq)
            val rideReached = BooleanArray(nq)
            for (p in 0 until day.patternCount) {
                val sp = startPos[p]
                if (sp == Int.MAX_VALUE) continue
                val quays = day.patternQuays[p]
                val n = quays.size
                var inst = -1
                var boardPos = -1
                for (pos in sp until n) {
                    val q = quays[pos]
                    if (inst >= 0) {
                        val arr = day.arrival(p, inst, pos)
                        if (arr < best[q] && arr < targetBest) {
                            tau[k][q] = arr; tauRide[k][q] = arr; best[q] = arr
                            rideInstance[k][q] = inst; rideBoardPos[k][q] = boardPos
                            rideAlightPos[k][q] = pos; ridePattern[k][q] = p
                            viaTransfer[k][q] = false
                            newlyMarked[q] = true; rideReached[q] = true
                        }
                    }
                    val prev = tau[k - 1][q]
                    val ready = if (prev == INF) INF else prev + walk.boardSlackSeconds
                    if (prev != INF && pos < n - 1 && (inst < 0 || ready <= day.departure(p, inst, pos))) {
                        val next = day.earliestInstance(p, pos, ready, excludedInstances)
                        if (next >= 0 && (inst < 0 || day.departure(p, next, pos) < day.departure(p, inst, pos))) {
                            inst = next; boardPos = pos
                        }
                    }
                }
            }
            // Walking transfers from quays reached by a ride in this round.
            // Ride labels are never overwritten by a transfer (separate arrays), so a transfer always
            // starts from a genuine ride arrival and reconstruction can't chain two walks.
            for (q in 0 until nq) {
                if (!rideReached[q]) continue
                val tr = transfers[q]
                var i = 0
                while (i < tr.size) {
                    val q2 = tr[i]; val secs = tr[i + 1]
                    val t = tauRide[k][q] + secs
                    if (t < best[q2] && t < targetBest) {
                        tau[k][q2] = t; best[q2] = t
                        transferFrom[k][q2] = q; transferSecs[k][q2] = secs; transferMeters[k][q2] = tr[i + 2]
                        viaTransfer[k][q2] = true
                        newlyMarked[q2] = true
                    }
                    i += 3
                }
            }
            // Destination reached in this round?
            var roundBest = INF
            var roundQuay = -1
            for (q in 0 until nq) {
                if (egressSecs[q] < 0 || tau[k][q] == INF) continue
                val t = tau[k][q] + egressSecs[q]
                if (t < roundBest) { roundBest = t; roundQuay = q }
            }
            if (roundQuay >= 0 && roundBest < targetBest) {
                targetBest = roundBest
                results += k to roundQuay
            }
            marked = newlyMarked
        }

        return results.map { (k, q) ->
            reconstruct(day, reverse, k, q, egressSecs[q], egressMeters[q], tau, tauRide, viaTransfer, rideInstance, rideBoardPos, rideAlightPos, ridePattern, transferFrom, transferSecs, transferMeters, accessSecs, accessMeters)
        }
    }

    private fun reconstruct(
        day: DayTimetable,
        reverse: Boolean,
        round: Int,
        egressQuay: Int,
        egressSec: Int,
        egressMeter: Int,
        tau: Array<IntArray>,
        tauRide: Array<IntArray>,
        viaTransfer: Array<BooleanArray>,
        rideInstance: Array<IntArray>,
        rideBoardPos: Array<IntArray>,
        rideAlightPos: Array<IntArray>,
        ridePattern: Array<IntArray>,
        transferFrom: Array<IntArray>,
        transferSecs: Array<IntArray>,
        transferMeters: Array<IntArray>,
        accessSecs: IntArray,
        accessMeters: IntArray,
    ): RaptorJourney {
        // Legs in search order (origin -> destination of the *search*), times in search space.
        data class L(val kind: Int, val a: Int, val b: Int, val from: Int, val to: Int, val meters: Int, val inst: Int = -1, val pattern: Int = -1)
        val legs = ArrayList<L>()
        legs += L(3, egressQuay, -1, tau[round][egressQuay], tau[round][egressQuay] + egressSec, egressMeter)
        var k = round
        var q = egressQuay
        while (k > 0) {
            if (viaTransfer[k][q]) {
                val from = transferFrom[k][q]
                legs += L(2, from, q, tauRide[k][from], tauRide[k][from] + transferSecs[k][q], transferMeters[k][q])
                q = from
            }
            val inst = rideInstance[k][q]
            val p = ridePattern[k][q]
            val bp = rideBoardPos[k][q]
            val ap = rideAlightPos[k][q]
            legs += L(1, bp, ap, day.departure(p, inst, bp), day.arrival(p, inst, ap), 0, inst, p)
            q = day.patternQuays[p][bp]
            k--
        }
        val accessStart = tau[0][q] - accessSecs[q]
        legs += L(0, q, -1, accessStart, tau[0][q], accessMeters[q])
        legs.reverse()
        // The access walk can start later than t0 (we only waited at the first quay).
        val firstRide = legs.firstOrNull { it.kind == 1 }
        if (firstRide != null && legs[0].kind == 0) {
            val walkSecs = legs[0].to - legs[0].from
            legs[0] = legs[0].copy(from = firstRide.from - walkSecs, to = firstRide.from)
        }
        // Map to real time.
        val origin = day.originEpoch
        fun real(t: Int): Long = if (reverse) -(origin + t) else origin + t
        val out = legs.map { l ->
            when (l.kind) {
                0 -> if (reverse) RaptorLeg.Egress(day.realQuay(l.a), real(l.to), real(l.from), l.meters)
                else RaptorLeg.Access(l.a, real(l.from), real(l.to), l.meters)
                3 -> if (reverse) RaptorLeg.Access(day.realQuay(l.a), real(l.to), real(l.from), l.meters)
                else RaptorLeg.Egress(l.a, real(l.from), real(l.to), l.meters)
                2 -> if (reverse) RaptorLeg.Transfer(day.realQuay(l.b), day.realQuay(l.a), real(l.to), real(l.from), l.meters)
                else RaptorLeg.Transfer(l.a, l.b, real(l.from), real(l.to), l.meters)
                else -> {
                    val instance = day.tripInstance(l.pattern, l.inst)
                    val pattern = day.realPattern(l.pattern)
                    val n = network.patterns[pattern].quays.size
                    if (reverse) {
                        RaptorLeg.Ride(instance, pattern, n - 1 - l.b, n - 1 - l.a, real(l.to), real(l.from))
                    } else {
                        RaptorLeg.Ride(instance, pattern, l.a, l.b, real(l.from), real(l.to))
                    }
                }
            }
        }
        // Reverse search legs run destination -> origin; flip them back.
        val ordered = if (reverse) out.reversed() else out
        // Arrive-by: the egress walk may end before the requested time - fine, it's "arrive by".
        return RaptorJourney(ordered)
    }

    private fun buildTransfers(): Array<IntArray> {
        val fp = footpaths
        return Array(network.quayCount) { q ->
            val range = fp.of(q)
            val out = IntArray((range.last - range.first + 1) * 3)
            var k = 0
            for (i in range) {
                out[k++] = fp.to[i]; out[k++] = walk.walkedSeconds(fp.meters[i].toDouble()); out[k++] = fp.meters[i]
            }
            out
        }
    }

    companion object {
        const val MAX_RIDES = 5
        private const val INF = Int.MAX_VALUE / 2
    }
}

/**
 * The trips of one query date, laid out for RAPTOR: per pattern, the trip instances of service days
 * D−1, D and D+1 that run, sorted by departure at the first stop, with their times in seconds from
 * [originEpoch] (D's service-day midnight). In reverse mode patterns are reversed and times negated.
 */
internal class DayTimetable private constructor(
    private val network: TransitNetwork,
    val originEpoch: Long,
    val reverse: Boolean,
    val patternCount: Int,
    val patternQuays: Array<IntArray>,
    /** Per pattern: instance trips / day offsets, and dep/arr matrices [instance * n + pos]. */
    private val instTrip: Array<IntArray>,
    private val instDay: Array<IntArray>,
    private val dep: Array<IntArray>,
    private val arr: Array<IntArray>,
    private val fifo: BooleanArray,
    val quayPatterns: Array<IntArray>,
    private val days: Array<LocalDate>,
    private val dayOffsets: IntArray,
) {
    fun departure(p: Int, inst: Int, pos: Int): Int = dep[p][inst * patternQuays[p].size + pos]
    fun arrival(p: Int, inst: Int, pos: Int): Int = arr[p][inst * patternQuays[p].size + pos]

    fun tripInstance(p: Int, i: Int): TripInstance = TripInstance(instTrip[p][i], days[instDay[p][i]])

    fun realPattern(p: Int): Int = p
    fun realQuay(q: Int): Int = q

    fun instanceOf(t: TripInstance): Int? {
        val p = network.tripPattern[t.trip]
        val d = days.indexOf(t.serviceDay).takeIf { it >= 0 } ?: return null
        for (i in instTrip[p].indices) if (instTrip[p][i] == t.trip && instDay[p][i] == d) return (p shl 16) or i
        return null
    }

    /**
     * Earliest instance of pattern [p] departing position [pos] at or after [time], or -1. [excluded]
     * holds `(pattern shl 16) or instance` keys (see [instanceOf]).
     */
    fun earliestInstance(p: Int, pos: Int, time: Int, excluded: Set<Int>): Int {
        val n = patternQuays[p].size
        val count = instTrip[p].size
        val d = dep[p]
        if (fifo[p] && excluded.isEmpty()) {
            var lo = 0; var hi = count
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (d[mid * n + pos] < time) lo = mid + 1 else hi = mid
            }
            return if (lo < count) lo else -1
        }
        var bestI = -1
        var bestT = Int.MAX_VALUE
        for (i in 0 until count) {
            val t = d[i * n + pos]
            if (t >= time && t < bestT && ((p shl 16) or i) !in excluded) { bestI = i; bestT = t }
        }
        return bestI
    }

    companion object {
        fun build(network: TransitNetwork, date: LocalDate, reverse: Boolean): DayTimetable {
            val origin = network.serviceDayStart(date)
            val days = arrayOf(date.minusDays(1), date, date.plusDays(1))
            val offsets = IntArray(3) { (network.serviceDayStart(days[it]) - origin).toInt() }
            val pc = network.patterns.size
            val patternQuays = Array(pc) { p ->
                val q = network.patterns[p].quays
                if (reverse) q.reversedArray() else q
            }
            val instTrip = arrayOfNulls<IntArray>(pc)
            val instDay = arrayOfNulls<IntArray>(pc)
            val depM = arrayOfNulls<IntArray>(pc)
            val arrM = arrayOfNulls<IntArray>(pc)
            val fifo = BooleanArray(pc)
            for (p in 0 until pc) {
                val n = patternQuays[p].size
                val trips = ArrayList<Long>() // (trip shl 2) or day
                for (d in 0..2) {
                    val di = network.dayIndex(days[d])
                    if (di < 0) continue
                    for (t in network.patternTripStart[p] until network.patternTripStart[p + 1]) {
                        if (network.isTripActive(t, di)) trips += (t.toLong() shl 2) or d.toLong()
                    }
                }
                val count = trips.size
                val dm = IntArray(count * n); val am = IntArray(count * n)
                val firstDep = IntArray(count)
                for ((i, packed) in trips.withIndex()) {
                    val t = (packed ushr 2).toInt(); val d = (packed and 3).toInt()
                    for (pos in 0 until n) {
                        if (!reverse) {
                            dm[i * n + pos] = offsets[d] + network.departureAt(t, pos)
                            am[i * n + pos] = offsets[d] + network.arrivalAt(t, pos)
                        } else {
                            val src = n - 1 - pos
                            dm[i * n + pos] = -(offsets[d] + network.arrivalAt(t, src))
                            am[i * n + pos] = -(offsets[d] + network.departureAt(t, src))
                        }
                    }
                    firstDep[i] = dm[i * n]
                }
                val order = (0 until count).sortedBy { firstDep[it] }
                val dmS = IntArray(count * n); val amS = IntArray(count * n)
                val it2 = IntArray(count); val id2 = IntArray(count)
                order.forEachIndexed { newI, oldI ->
                    System.arraycopy(dm, oldI * n, dmS, newI * n, n)
                    System.arraycopy(am, oldI * n, amS, newI * n, n)
                    it2[newI] = (trips[oldI] ushr 2).toInt(); id2[newI] = (trips[oldI] and 3).toInt()
                }
                var isFifo = true
                loop@ for (pos in 0 until n) for (i in 1 until count) {
                    if (dmS[i * n + pos] < dmS[(i - 1) * n + pos]) { isFifo = false; break@loop }
                }
                instTrip[p] = it2; instDay[p] = id2; depM[p] = dmS; arrM[p] = amS; fifo[p] = isFifo
            }
            // Patterns through each quay (positions in the possibly reversed pattern).
            val lists = Array(network.quayCount) { ArrayList<Int>() }
            for (p in 0 until pc) patternQuays[p].forEachIndexed { pos, q -> lists[q] += p; lists[q] += pos }
            return DayTimetable(
                network, if (reverse) -origin else origin, reverse, pc, patternQuays,
                instTrip.requireNoNulls(), instDay.requireNoNulls(), depM.requireNoNulls(), arrM.requireNoNulls(), fifo,
                Array(network.quayCount) { lists[it].toIntArray() }, days, offsets,
            )
        }
    }
}

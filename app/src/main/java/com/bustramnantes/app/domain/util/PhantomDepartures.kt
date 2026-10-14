package com.bustramnantes.app.domain.util

import com.bustramnantes.app.domain.model.Departure
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant

/**
 * A theoretical departure closer than this, on a line that does have real-time data, is suspected
 * to be a phantom (see [tagSuspectedPhantoms]).
 *
 * Measured, provisional: in a 15-min study of 48 stops (evening peak) every theoretical
 * slot that turned out to be a real bus switched to real-time **8 min or more** before its time,
 * while the ones that never went live stayed theoretical on the Naolib board until ~30 s before.
 * Small sample (11 slots) - widen/narrow once a longer study confirms it.
 */
val PHANTOM_IMMINENT_WINDOW = 8.minutes

/**
 * Flags the theoretical departures of one stop's real-time board that are probably **phantoms** -
 * timetable slots the Naolib board (and therefore the official app) keeps announcing although no
 * vehicle is running them (cancelled, or simply never tracked). Riders report these as "the app said
 * 3 min, the bus never came, the street screen didn't even list it".
 *
 * A departure is tagged ([Departure.isSuspectedPhantom]) when ALL of:
 *  - it is theoretical ([Departure.isRealtime] is false), and
 *  - its line has real-time data on this board (some departure of the same line is real-time).
 *    Lines with no real-time feed at all - verified network-wide: TE2, N3, 1B, the
 *    suburban 118/131/137/147 - only ever show theoretical times, even a minute away; tagging those
 *    would hide perfectly real buses, so they are left alone; and
 *  - either it is **imminent** (≤ [PHANTOM_IMMINENT_WINDOW] away), or it is **sandwiched** between
 *    real-time departures of the same line+direction - the vehicles before and after it are being
 *    tracked, so the one in between would be too if it existed.
 *
 * Nothing is removed: callers decide how to present a tagged departure. Measured share: ~1.8% of
 * all departures within the hour, network-wide (3 sweeps of ~1180 stops, 18:00-18:15).
 *
 * Only meaningful on a board with a complete real-time source: fresh SIRI (where "theoretical" means
 * the trip isn't listed, or is > 50 min away - see `BoardComposer`).
 * Never applied to provisional boards: GTFS-RT omits ~27 % of tracked vehicles, so a trip's absence
 * there proves nothing. The rule was calibrated on the official app's board and holds on SIRI: weekday
 * and evening-peak studies (SOURCES.md §9b) found no looser rule without false alarms.
 */
fun List<Departure>.tagSuspectedPhantoms(now: Instant): List<Departure> {
    val linesWithRealtime = asSequence().filter { it.isRealtime }.map { it.lineNumber }.toSet()
    val realtimeTimesByKey = filter { it.isRealtime }
        .groupBy({ it.lineNumber to it.direction.code }, { it.nextDepartedTime })
    return map { departure ->
        if (departure.isRealtime || departure.lineNumber !in linesWithRealtime) return@map departure
        val time = departure.nextDepartedTime
        val realtime = realtimeTimesByKey[departure.lineNumber to departure.direction.code].orEmpty()
        val imminent = time - now <= PHANTOM_IMMINENT_WINDOW
        val sandwiched = realtime.any { it < time } && realtime.any { it > time }
        if (imminent || sandwiched) departure.copy(isSuspectedPhantom = true) else departure
    }
}

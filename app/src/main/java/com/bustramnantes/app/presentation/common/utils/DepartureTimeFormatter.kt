package com.bustramnantes.app.presentation.common.utils

import com.bustramnantes.app.domain.model.Departure
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong

/**
 * The Naolib network's timezone. Departure clock times are shown in Europe/Paris regardless of the
 * device's timezone: a departure is a wall-clock event at the stop in Nantes, so a traveller whose
 * phone is set to another zone should still see the local Nantes "(HH:mm)". (The relative "N min"
 * is timezone-independent and unaffected.)
 */
val NANTES_TIME_ZONE: TimeZone = TimeZone.of("Europe/Paris")

/**
 * Formats a departure instant as the `"3 min (17:43)"` string:
 * relative time first, absolute clock time in parentheses.
 *
 * [departureTime] is taken as-is - it is already the time to display, whether a real-time
 * prediction or theoretical. Callers that need the realtime/theoretical indicator read
 * [Departure.isRealtime] separately, e.g. via
 * `com.bustramnantes.app.presentation.common.components.DepartureTimeText`.
 *
 * Negative remaining time (clock skew, or a departure that has just passed) is clamped to
 * `"0 min"` rather than shown as negative.
 *
 * @return e.g. `"3 min (17:43)"`.
 */
fun formatDepartureTime(
    departureTime: Instant,
    now: Instant = Clock.System.now(),
    zone: TimeZone = NANTES_TIME_ZONE,
): String {
    val remainingMinutes = ((departureTime - now).inWholeSeconds / 60.0)
        .roundToLong()
        .coerceAtLeast(0L)
    val local = departureTime.toLocalDateTime(zone)
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$remainingMinutes min ($hh:$mm)"
}

/** Convenience overload reading [Departure.nextDepartedTime] directly. */
fun formatDepartureTime(
    departure: Departure,
    now: Instant = Clock.System.now(),
    zone: TimeZone = NANTES_TIME_ZONE,
): String = formatDepartureTime(departure.nextDepartedTime, now, zone)

/**
 * Label for a departure tagged [Departure.isSuspectedPhantom]: a scheduled time no tracked vehicle
 * is running, which the street screens don't show and which most often never comes. Shown next to
 * the struck-through time. "Probablement" because it is an inference, not an announcement.
 */
const val PHANTOM_DEPARTURE_LABEL = "probablement supprimé"

/**
 * Label shown when a stop/line/direction has no more departures today. Worded
 * "Aucun passage à venir" so it reads correctly for both bus and tram lines.
 */
const val END_OF_SERVICE_LABEL = "Aucun passage à venir"

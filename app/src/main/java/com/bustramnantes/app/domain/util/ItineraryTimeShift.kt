package com.bustramnantes.app.domain.util

import com.bustramnantes.app.domain.model.ItineraryTimePreference
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * How far ahead an itinerary may be scheduled - shared by the search form's date picker and the
 * results screen's "Arriver plus tôt / plus tard" buttons, so neither can land on a date the other
 * would refuse.
 *
 * Itineraries are planned on the installed GTFS feed, which covers ~3 months and is replaced as new
 * publications appear; past its last day there is no transit at all. The picker therefore stops at
 * `min(today + 90 days, last day of the installed feed)` (see [lastSchedulableDate]); a day with no
 * service inside that window is covered by the results screen's "Aucun horaire de transport
 * disponible pour cette date." note. Never hard-code a date here.
 */
const val MAX_SCHEDULE_DAYS_AHEAD = 90

/** Step of the results screen's "Arriver plus tôt" / "Arriver plus tard" buttons. */
const val ITINERARY_TIME_SHIFT_MINUTES = 15

/**
 * Shifts an itinerary search's time by [deltaMinutes] (negative = earlier), **keeping its time
 * mode**: "Partir à 10:17" ±15 → "Partir à 10:02 / 10:32", "Arriver à 21:05" ±15 → "Arriver à
 * 20:50 / 21:20". The date rolls over across midnight (23:55 + 15 → 00:10 the next day).
 *
 * [ItineraryTimePreference.Now] has no instant to shift *earlier* (`null`: the button is disabled);
 * shifting it *later* turns it into "Partir à" [now] + [deltaMinutes], seconds dropped - the form's
 * picker only deals in whole minutes.
 *
 * Returns `null` too when the shifted date falls outside the date picker's window
 * `[today, lastSchedulableDate(feedEnd)]` (today = [now]'s date): past the forward edge there is no
 * transit, and before today the picker would just coerce the date back - either way the search form
 * could not represent the result.
 *
 * The arithmetic runs on wall-clock time in [timeZone] (UTC by default - no DST transitions, so a
 * pure local "+15 min"; pass the network's zone to have a DST-gap time normalised the way the
 * backend would read it).
 */
fun shiftItineraryTime(
    preference: ItineraryTimePreference,
    deltaMinutes: Int,
    now: LocalDateTime,
    timeZone: TimeZone = TimeZone.UTC,
    feedEnd: LocalDate? = null,
): ItineraryTimePreference? {
    val shifted = when (preference) {
        ItineraryTimePreference.Now -> {
            if (deltaMinutes <= 0) return null
            val nowToTheMinute = LocalDateTime(now.date, LocalTime(now.hour, now.minute))
            ItineraryTimePreference.DepartAt(nowToTheMinute.plusMinutes(deltaMinutes, timeZone))
        }
        is ItineraryTimePreference.DepartAt ->
            ItineraryTimePreference.DepartAt(preference.dateTime.plusMinutes(deltaMinutes, timeZone))
        is ItineraryTimePreference.ArriveBy ->
            ItineraryTimePreference.ArriveBy(preference.dateTime.plusMinutes(deltaMinutes, timeZone))
    }
    val date = shifted.scheduledDateTime?.date ?: return null
    return shifted.takeIf { date in now.date..now.date.lastSchedulableDate(feedEnd) }
}

/**
 * Last date the itinerary date picker offers when today is `this`: [MAX_SCHEDULE_DAYS_AHEAD] days
 * ahead, or the installed feed's last day ([feedEnd]) when sooner (never before today).
 */
fun LocalDate.lastSchedulableDate(feedEnd: LocalDate? = null): LocalDate {
    val rolling = plus(MAX_SCHEDULE_DAYS_AHEAD, DateTimeUnit.DAY)
    return if (feedEnd != null && feedEnd < rolling) maxOf(feedEnd, this) else rolling
}

private fun LocalDateTime.plusMinutes(minutes: Int, timeZone: TimeZone): LocalDateTime =
    toInstant(timeZone).plus(minutes, DateTimeUnit.MINUTE).toLocalDateTime(timeZone)

package com.bustramnantes.app.domain.model

import kotlinx.datetime.LocalDateTime

/**
 * When the user wants to travel - the time half of an itinerary search.
 *
 * Modelled as a sealed hierarchy rather than a nullable `LocalDateTime` because there are three cases,
 * not two: a timestamp alone is ambiguous between "leave at this time" and "be there by this time".
 * The on-device planner runs a forward RAPTOR search for the first two and a reverse one for
 * [ArriveBy].
 */
sealed interface ItineraryTimePreference {

    /** "Partir maintenant" - planned from the device clock. */
    data object Now : ItineraryTimePreference

    /** "Partir à" - the first journey leaves at/after [dateTime]. */
    data class DepartAt(val dateTime: LocalDateTime) : ItineraryTimePreference

    /** "Arriver à" - the last journey leg lands at/just before [dateTime] . */
    data class ArriveBy(val dateTime: LocalDateTime) : ItineraryTimePreference

    /**
     * The scheduled instant, or `null` for [Now]. Handy for callers that only need the *date* of the
     * search (e.g. to build a theoretical-timetable route) and don't care which end it anchors.
     */
    val scheduledDateTime: LocalDateTime?
        get() = when (this) {
            Now -> null
            is DepartAt -> dateTime
            is ArriveBy -> dateTime
        }
}

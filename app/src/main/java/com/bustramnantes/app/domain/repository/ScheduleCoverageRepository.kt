package com.bustramnantes.app.domain.repository

import kotlinx.datetime.LocalDate

/** How far ahead the installed timetable (GTFS feed) reaches. */
fun interface ScheduleCoverageRepository {
    /** Last service day of the installed feed, or null when unknown. */
    suspend fun lastScheduledDate(): LocalDate?
}

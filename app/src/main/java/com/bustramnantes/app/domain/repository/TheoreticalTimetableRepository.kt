package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.TheoreticalTimetable
import kotlinx.datetime.LocalDate

/**
 * Theoretical (scheduled) timetables for a line+direction at a stop on a chosen date, computed from
 * the local GTFS network - offline and instant, so there is nothing to prefetch or cache.
 */
interface TheoreticalTimetableRepository {

    /**
     * A [DataResult.Success] with [TheoreticalTimetable.isEmpty] `== true` means nothing runs on [date]
     * (empty day / outside the installed feed), which is NOT an error.
     *
     * @param stopId any id of the stop ([com.bustramnantes.app.domain.model.Stop.id] or a member).
     * @param lineNumber line short name (e.g. `"11"`, `"C2"`).
     * @param directionId 1-based direction id (GTFS `direction_id` + 1).
     */
    suspend fun getTimetable(
        stopId: String,
        lineNumber: String,
        directionId: Int,
        date: LocalDate,
    ): DataResult<TheoreticalTimetable>
}

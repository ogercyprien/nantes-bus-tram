package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.TheoreticalTimetable
import com.bustramnantes.app.domain.repository.TheoreticalTimetableRepository
import javax.inject.Inject
import kotlinx.datetime.LocalDate

/**
 * The theoretical timetable for one line+direction at a stop on a date - the data behind the
 * "Horaires théoriques" screen. A thin wrapper over [TheoreticalTimetableRepository] (local GTFS, no
 * polling: schedules don't change under you).
 */
class GetTheoreticalTimetableUseCase @Inject constructor(
    private val repository: TheoreticalTimetableRepository,
) {
    suspend operator fun invoke(
        stopId: String,
        lineNumber: String,
        directionId: Int,
        date: LocalDate,
    ): DataResult<TheoreticalTimetable> = repository.getTimetable(stopId, lineNumber, directionId, date)
}

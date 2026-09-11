package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.TransitNetworkRepository
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.TheoreticalTimetable
import com.bustramnantes.app.domain.model.TimetableEntry
import com.bustramnantes.app.domain.repository.TheoreticalTimetableRepository
import com.bustramnantes.transit.timetable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate

/**
 * Day timetables from the local GTFS network ([com.bustramnantes.transit.timetable]): every
 * StopPlace of the (possibly merged) stop, one line, one direction, one service day. After-midnight
 * runs come last with `nextDay`; each run carries its own headsign, so short runs show their terminus.
 */
@Singleton
class TheoreticalTimetableRepositoryImpl @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : TheoreticalTimetableRepository {

    override suspend fun getTimetable(
        stopId: String,
        lineNumber: String,
        directionId: Int,
        date: LocalDate,
    ): DataResult<TheoreticalTimetable> = runCatching {
        val index = networkRepository.index()
        val network = index.network
        val route = network.routeIndex(lineNumber)
        val rows = if (route == null) {
            emptyList()
        } else {
            index.stopPlaces(stopId)
                .flatMap { sp -> network.timetable(sp, route, directionId - 1, date.toJavaLocalDate()) }
                .sortedWith(compareBy({ it.nextDay }, { it.time }))
        }
        val entries = rows.map { row ->
            TimetableEntry(
                time = LocalTime(row.time.hour, row.time.minute),
                nextDay = row.nextDay,
                destinationLabel = row.headsign.ifBlank { null },
            )
        }
        TheoreticalTimetable(
            stopId = stopId,
            lineNumber = lineNumber,
            directionId = directionId,
            date = date,
            destinationLabels = entries.mapNotNull { it.destinationLabel }.distinct(),
            entries = entries,
        )
    }.toDataResult()
}

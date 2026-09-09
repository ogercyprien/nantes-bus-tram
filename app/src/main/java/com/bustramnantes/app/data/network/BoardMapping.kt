package com.bustramnantes.app.data.network

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.DepartureSource
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.transit.realtime.BoardComposer
import com.bustramnantes.transit.realtime.BoardEntry
import com.bustramnantes.transit.realtime.EntrySource
import kotlinx.datetime.Instant

/** How far ahead boards reach (the official board shows ≥ 2 h). */
const val BOARD_HORIZON_SECONDS = 2 * 3600L

/** A [BoardComposer] over this index's network with the app's horizons. */
fun NetworkIndex.boardComposer(): BoardComposer = BoardComposer(network, horizonSeconds = BOARD_HORIZON_SECONDS)

/**
 * A composed board entry as a domain [Departure], with the GTFS line identity (id, colours, mode,
 * canonical direction name) so it matches favorites and served lines. An entry whose line the network
 * doesn't know (newer feed) keeps SIRI's short name with neutral colours.
 */
fun NetworkIndex.toDeparture(entry: BoardEntry): Departure {
    val line = entry.route?.let { lines[it] } ?: line(entry.lineShortName)
    val direction = entry.directionId?.let { dir -> line?.directionFor(dir) ?: Direction(dir.toString(), entry.destination) }
        ?: Direction("", entry.destination)
    val time = Instant.fromEpochSeconds(entry.departureEpochSecond)
    return Departure(
        lineId = line?.id ?: entry.lineShortName,
        lineNumber = line?.shortName ?: entry.lineShortName,
        lineName = line?.longName ?: entry.lineShortName,
        lineColor = line?.let { "#" + it.color } ?: PLACEHOLDER_COLOR,
        lineTextColor = line?.let { "#" + it.textColor } ?: PLACEHOLDER_TEXT_COLOR,
        transportMode = line?.mode ?: TransportMode.BUS,
        direction = direction,
        nextDepartedTime = time,
        nextArrivalTime = time,
        isRealtime = entry.isRealtime,
        scheduledTime = entry.scheduledEpochSecond?.let { Instant.fromEpochSeconds(it) },
        tripId = entry.tripKey,
        destinationName = entry.destination.ifBlank { null },
        source = when (entry.source) {
            EntrySource.SIRI -> DepartureSource.SIRI
            EntrySource.GTFS_RT -> DepartureSource.GTFS_RT
            EntrySource.TIMETABLE -> DepartureSource.TIMETABLE
        },
    )
}

/** Timetabled departures for the next [BOARD_HORIZON_SECONDS] (offline fallback). */
fun NetworkIndex.scheduledDepartures(stopId: String, now: Instant): List<Departure> {
    val quays = quays(stopId)
    if (quays.isEmpty()) return emptyList()
    return boardComposer().composeTimetable(quays, now.epochSeconds).map(::toDeparture)
}

private const val PLACEHOLDER_COLOR = "#5F6368"
private const val PLACEHOLDER_TEXT_COLOR = "#FFFFFF"

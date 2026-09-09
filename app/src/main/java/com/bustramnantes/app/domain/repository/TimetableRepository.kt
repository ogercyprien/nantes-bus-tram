package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.ServedLine

/**
 * A stop's served lines. Departure boards themselves come from [RealtimeBoardRepository].
 */
interface TimetableRepository {

    /**
     * The lines/directions boardable at a stop, from the GTFS patterns: independent of service hours
     * (so Stop Detail keeps an end-of-service row) and terminus-aware (a direction the stop only ends
     * is left out). Offline. Empty for an unknown stop.
     */
    suspend fun getServedLines(stopId: String): List<ServedLine>
}

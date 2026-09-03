package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Line
import kotlinx.coroutines.flow.Flow

/** Access to the lines of the installed GTFS network (bundled seed or the last downloaded feed). */
interface LineRepository {

    fun observeLines(): Flow<List<Line>>

    suspend fun getLineById(id: String): Line?

    suspend fun getLineByShortName(shortName: String): Line?

    /** Checks for a newer GTFS publication and installs it (shared with [StopRepository.refreshStops]). */
    suspend fun refreshLines(): DataResult<Unit>

    /** Forgets the downloaded network, back to the bundled seed (used together with [refreshLines] by "Effacer"). */
    suspend fun clearLinesCache()

    /** Epoch ms the installed network was written, or `null` when running on the seed. */
    suspend fun getLinesCacheTimestamp(): Long?
}

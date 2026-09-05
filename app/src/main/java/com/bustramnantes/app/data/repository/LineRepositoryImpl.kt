package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.TransitNetworkRepository
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.repository.LineRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lines from the static GTFS network (`routes.txt`): id `FR_NAOLIB:Line:<short>`, colours without
 * `#`, mode from `route_type`, and the two direction names (most common headsign per
 * `direction_id`). Offline from first launch via the bundled seed; the
 * refresh/clear/timestamp operations all act on the one shared network (see [StopRepositoryImpl]).
 */
@Singleton
class LineRepositoryImpl @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : LineRepository {

    override fun observeLines(): Flow<List<Line>> = networkRepository.indexes.map { it.lines }

    override suspend fun getLineById(id: String): Line? = networkRepository.index().lines.firstOrNull { it.id == id }

    override suspend fun getLineByShortName(shortName: String): Line? = networkRepository.index().line(shortName)

    override suspend fun refreshLines(): DataResult<Unit> =
        when (val result = networkRepository.refresh(allowMetered = true)) {
            is DataResult.Success -> DataResult.Success(Unit)
            is DataResult.Error -> result
        }

    override suspend fun clearLinesCache() = networkRepository.resetToSeed()

    override suspend fun getLinesCacheTimestamp(): Long? = networkRepository.installedAt()
}

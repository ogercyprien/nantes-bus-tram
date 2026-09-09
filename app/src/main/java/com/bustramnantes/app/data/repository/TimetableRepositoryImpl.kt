package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.TransitNetworkRepository
import com.bustramnantes.app.domain.model.ServedLine
import com.bustramnantes.app.domain.repository.TimetableRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Served lines from the local GTFS network. Nothing is cached here. */
@Singleton
class TimetableRepositoryImpl @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : TimetableRepository {

    override suspend fun getServedLines(stopId: String): List<ServedLine> =
        networkRepository.index().servedLines(stopId)
}

package com.bustramnantes.app.data.network

import com.bustramnantes.app.domain.repository.ScheduleCoverageRepository
import javax.inject.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate

/** The installed network's validity end (refreshed feeds extend it as they are installed). */
class ScheduleCoverageRepositoryImpl @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : ScheduleCoverageRepository {
    override suspend fun lastScheduledDate(): LocalDate = networkRepository.network().validUntil.toKotlinLocalDate()
}

/** [TransitDataDiagnostics] backed by the network repository (debug Settings action). */
class TransitDataDiagnosticsImpl @Inject constructor(
    private val networkRepository: TransitNetworkRepository,
) : com.bustramnantes.app.domain.repository.TransitDataDiagnostics {
    override suspend fun convertNow(): String = networkRepository.debugConvertNow()
}

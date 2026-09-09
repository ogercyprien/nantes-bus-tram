package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.DepartureSource
import com.bustramnantes.app.domain.model.StopBoard
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** Fallback re-ask cadence for live departures ("auto-refresh every 30s"). */
const val TIMETABLE_REFRESH_INTERVAL_MS = 30_000L

/**
 * A stop's departure board, re-emitted for as long as the returned [Flow] is collected: once
 * immediately (SIRI-based when fresh, else provisional - see [RealtimeBoardRepository.boards]), then
 * again as soon as a real-time fetch lands ([RealtimeBoardRepository.updates]) or at the latest every
 * [refreshIntervalMs]. Asking registers the stop with the highest priority, so the next SIRI slot
 * covers it - a provisional board is typically replaced within one slot (≤ 31 s).
 *
 * Collect it from `viewModelScope`: it self-cancels with the collecting scope (sheet dismissed), after
 * which the stop's interest lapses and the shared poller stops if nothing else is watched.
 */
class GetStopTimetableUseCase @Inject constructor(
    private val realtime: RealtimeBoardRepository,
) {
    operator fun invoke(
        stopId: String,
        refreshIntervalMs: Long = TIMETABLE_REFRESH_INTERVAL_MS,
    ): Flow<StopBoard> = flow {
        while (true) {
            emit(
                realtime.boards(listOf(stopId), RealtimePriority.OPEN_STOP)[stopId]
                    ?: StopBoard(emptyList(), provisional = false, source = DepartureSource.TIMETABLE),
            )
            withTimeoutOrNull(refreshIntervalMs) { realtime.updates.first() }
        }
    }
}

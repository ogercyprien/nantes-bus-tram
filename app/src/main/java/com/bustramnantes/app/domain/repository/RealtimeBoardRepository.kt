package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.StopBoard
import kotlinx.coroutines.flow.Flow

/** Who wants a stop's real-time board - decides which quays a size-capped SIRI batch carries. */
enum class RealtimePriority {
    PREFETCH,
    FAVORITES,
    ITINERARY,
    OPEN_STOP,
}

/**
 * Real-time departure boards (Okina SIRI, one shared request budget - see
 * `data/realtime/RealtimeGateway`). Asking for a stop both returns the best board available **now**
 * and registers interest, so the next SIRI slot fetches it; interest lapses ~75 s after the last ask,
 * so callers simply re-ask on each poll.
 */
interface RealtimeBoardRepository {

    /**
     * The best board available right now for each of [stopIds]: SIRI-based when fresh (or a
     * cached SIRI snapshot ≤ 5 min old), else provisional (GTFS-RT overlay or timetable). Never
     * fails: the timetable is local. An unknown stop gets an empty board.
     */
    suspend fun boards(stopIds: Collection<String>, priority: RealtimePriority): Map<String, StopBoard>

    /** Emits after every real-time fetch (success or failure) - a cue to call [boards] again. */
    val updates: Flow<Unit>

    /**
     * Registers [stopIds] and waits (at most [timeoutMs]) until a SIRI fetch has covered them, then
     * returns their boards (provisional ones if the wait timed out).
     */
    suspend fun awaitRealtime(stopIds: Collection<String>, priority: RealtimePriority, timeoutMs: Long): Map<String, StopBoard>

    /**
     * Fetches the whole network's real-time data in the background (app brought to the foreground),
     * rate-limited by the implementation. Fire-and-forget.
     */
    fun prefetchNetwork() {}
}

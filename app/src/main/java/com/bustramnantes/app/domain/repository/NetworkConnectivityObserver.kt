package com.bustramnantes.app.domain.repository

import kotlinx.coroutines.flow.Flow

/** Backs the offline banner shown on every screen. */
interface NetworkConnectivityObserver {

    /** Emits the current connectivity state immediately on collection, then on every change. */
    val isOnline: Flow<Boolean>

    /**
     * `true` when the active network is **metered** (cellular, or a metered Wi-Fi/hotspot). Emits the
     * current value immediately on collection, then on every change. Used to gate speculative,
     * non-essential background traffic (e.g. theoretical-timetable prefetch) so it never spends the
     * user's cellular data - see `PrefetchTimetablesUseCase`. Reports `false` when offline/unknown
     * (callers that care about reachability check [isOnline] separately).
     */
    val isMetered: Flow<Boolean>
}

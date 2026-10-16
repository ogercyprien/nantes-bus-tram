package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.Disruption
import kotlinx.coroutines.flow.StateFlow

/**
 * Network disruption notices (Okina GTFS-RT Alerts - see `data/realtime/GtfsRtDisruptionRepository`).
 * The last fetched feed is kept on disk, so notices show offline and right after a restart.
 */
interface DisruptionRepository {

    /** The last known notices, including future and expired ones (filter with `DisruptionRelevance`). */
    val disruptions: StateFlow<List<Disruption>>

    /**
     * Fetches the feed when the last fetch is older than a few minutes. Shares the GTFS-RT request
     * budget (1 request / 30 s) with the provisional boards, so it may wait up to ~30 s for a slot -
     * call it from a background coroutine. Never throws: a failure keeps the last known notices.
     */
    suspend fun refreshIfStale()
}

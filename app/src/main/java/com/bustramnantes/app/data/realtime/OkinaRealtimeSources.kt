package com.bustramnantes.app.data.realtime

import com.bustramnantes.app.data.remote.okina.OkinaClient
import com.bustramnantes.transit.realtime.SiriVisit
import com.bustramnantes.transit.realtime.TripUpdatesFeed
import javax.inject.Inject

/** Production [RealtimeSources]: Okina SIRI + GTFS-RT. */
class OkinaRealtimeSources @Inject constructor(
    private val okina: OkinaClient,
) : RealtimeSources {
    override suspend fun stopMonitoring(quayIds: Collection<String>, bulk: Boolean): List<SiriVisit> =
        okina.stopMonitoring(quayIds, bulk)

    override suspend fun tripUpdates(): TripUpdatesFeed = okina.tripUpdates()
}

package com.bustramnantes.app.di

import com.bustramnantes.app.data.local.AppPreferencesDataStore
import com.bustramnantes.app.data.network.TransitNetworkRepository
import android.content.Context
import com.bustramnantes.app.data.realtime.FileAlertsStore
import com.bustramnantes.app.data.realtime.GtfsRtDisruptionRepository
import com.bustramnantes.app.data.realtime.NetworkIndexSource
import com.bustramnantes.app.data.realtime.OkinaRealtimeSources
import com.bustramnantes.app.data.realtime.RealtimeGateway
import com.bustramnantes.app.data.realtime.RequestBudget
import com.bustramnantes.app.data.remote.okina.OkinaClient
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Qualifies the one GTFS-RT [RequestBudget] (TripUpdates and Alerts share Okina's GTFS-RT quota). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GtfsRtBudget

/**
 * Wires the single [RealtimeGateway] and the disruption notices. The SIRI budget persists its
 * last-request time in DataStore (shared by the UI and the widget worker, kept across restarts); the
 * GTFS-RT budget lives in memory and is shared by the provisional boards (TripUpdates) and
 * [GtfsRtDisruptionRepository] (Alerts) - one Okina quota for both.
 */
@Module
@InstallIn(SingletonComponent::class)
object RealtimeModule {

    @Provides
    @Singleton
    @GtfsRtBudget
    fun provideGtfsRtBudget(): RequestBudget {
        var last = 0L
        return RequestBudget(
            minIntervalMs = RealtimeGateway.GTFS_RT_MIN_INTERVAL_MS,
            load = { last },
            store = { last = it },
        )
    }

    @Provides
    @Singleton
    fun provideRealtimeGateway(
        networkRepository: TransitNetworkRepository,
        sources: OkinaRealtimeSources,
        preferences: AppPreferencesDataStore,
        @GtfsRtBudget gtfsRtBudget: RequestBudget,
    ): RealtimeGateway {
        return RealtimeGateway(
            indexSource = NetworkIndexSource { networkRepository.index() },
            sources = sources,
            siriBudget = RequestBudget(
                minIntervalMs = RealtimeGateway.SIRI_MIN_INTERVAL_MS,
                load = { preferences.lastSiriRequestAt() },
                store = { preferences.setLastSiriRequestAt(it) },
            ),
            gtfsRtBudget = gtfsRtBudget,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    @Provides
    @Singleton
    fun provideDisruptionRepository(
        @ApplicationContext context: Context,
        networkRepository: TransitNetworkRepository,
        okina: OkinaClient,
        @GtfsRtBudget gtfsRtBudget: RequestBudget,
    ): DisruptionRepository = GtfsRtDisruptionRepository(
        indexSource = NetworkIndexSource { networkRepository.index() },
        fetch = { okina.alerts() },
        budget = gtfsRtBudget,
        store = FileAlertsStore(File(context.filesDir, "realtime/alerts.pb")),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Provides
    fun provideRealtimeBoardRepository(gateway: RealtimeGateway): RealtimeBoardRepository = gateway
}

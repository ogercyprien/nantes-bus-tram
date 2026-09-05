package com.bustramnantes.app.di

import com.bustramnantes.app.data.repository.FavoriteRepositoryImpl
import com.bustramnantes.app.data.repository.GeocodingRepositoryImpl
import com.bustramnantes.app.data.repository.LocalItineraryRepository
import com.bustramnantes.app.data.network.ScheduleCoverageRepositoryImpl
import com.bustramnantes.app.data.network.TransitDataDiagnosticsImpl
import com.bustramnantes.app.domain.repository.TransitDataDiagnostics
import com.bustramnantes.app.domain.repository.ScheduleCoverageRepository
import com.bustramnantes.app.data.repository.LineRepositoryImpl
import com.bustramnantes.app.data.repository.NetworkConnectivityObserverImpl
import com.bustramnantes.app.data.repository.RecentSearchRepositoryImpl
import com.bustramnantes.app.data.repository.StopRepositoryImpl
import com.bustramnantes.app.data.repository.TheoreticalTimetableRepositoryImpl
import com.bustramnantes.app.data.repository.TimetableRepositoryImpl
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.GeocodingRepository
import com.bustramnantes.app.domain.repository.ItineraryRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.NetworkConnectivityObserver
import com.bustramnantes.app.domain.repository.RecentSearchRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.repository.TheoreticalTimetableRepository
import com.bustramnantes.app.domain.repository.TimetableRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds every `domain/repository/` interface to its `data/repository/` implementation.
 * All bindings are `@Singleton`:
 *
 * - [NetworkConnectivityObserver] must be a singleton: a single shared connectivity stream.
 * - [StopRepository] keeps a per-network search index, so a single instance avoids rebuilding it.
 * - The remaining repositories are stateless wrappers around already-singleton Room DAOs /
 *   Retrofit services, so `@Singleton` here is just a minor efficiency choice, not a correctness
 *   requirement.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindStopRepository(impl: StopRepositoryImpl): StopRepository

    @Binds
    @Singleton
    abstract fun bindLineRepository(impl: LineRepositoryImpl): LineRepository

    @Binds
    @Singleton
    abstract fun bindTimetableRepository(impl: TimetableRepositoryImpl): TimetableRepository

    @Binds
    @Singleton
    abstract fun bindTheoreticalTimetableRepository(
        impl: TheoreticalTimetableRepositoryImpl,
    ): TheoreticalTimetableRepository

    @Binds
    @Singleton
    abstract fun bindItineraryRepository(impl: LocalItineraryRepository): ItineraryRepository

    @Binds
    abstract fun bindTransitDataDiagnostics(impl: TransitDataDiagnosticsImpl): TransitDataDiagnostics

    @Binds
    abstract fun bindScheduleCoverageRepository(impl: ScheduleCoverageRepositoryImpl): ScheduleCoverageRepository

    @Binds
    @Singleton
    abstract fun bindFavoriteRepository(impl: FavoriteRepositoryImpl): FavoriteRepository

    @Binds
    @Singleton
    abstract fun bindRecentSearchRepository(impl: RecentSearchRepositoryImpl): RecentSearchRepository

    @Binds
    @Singleton
    abstract fun bindGeocodingRepository(impl: GeocodingRepositoryImpl): GeocodingRepository

    @Binds
    @Singleton
    abstract fun bindNetworkConnectivityObserver(
        impl: NetworkConnectivityObserverImpl,
    ): NetworkConnectivityObserver
}

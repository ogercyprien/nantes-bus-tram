package com.bustramnantes.app.di

import com.bustramnantes.app.presentation.search.location.LocationProvider
import com.bustramnantes.app.presentation.search.location.UserLocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the [LocationProvider] seam to the framework-backed [UserLocationProvider]. The interface
 * exists so location-consuming ViewModels (e.g. `NearbyStopsViewModel`) can be unit-tested with a
 * fake; production always uses the real device lookup. [UserLocationProvider] is still directly
 * injectable via its `@Inject` constructor for callers that depend on the concrete type.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {

    @Binds
    abstract fun bindLocationProvider(impl: UserLocationProvider): LocationProvider
}

package com.bustramnantes.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Qualifies the IO [CoroutineDispatcher] so it can be swapped for a test dispatcher in unit tests. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Provides the app's [CoroutineDispatcher]s behind qualifiers. Injecting the dispatcher (rather than
 * referencing [Dispatchers.IO] directly) lets tests substitute a deterministic test dispatcher, so
 * coroutine-heavy singletons can be exercised without real threads or wall-clock delays.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

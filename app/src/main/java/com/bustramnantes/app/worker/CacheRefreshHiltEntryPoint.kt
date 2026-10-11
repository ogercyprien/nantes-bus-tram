package com.bustramnantes.app.worker

import com.bustramnantes.app.data.network.TransitNetworkRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Manual Hilt entry point for [CacheRefreshWorker]. See
 * [com.bustramnantes.app.widget.WidgetHiltEntryPoint]'s doc for why plain `CoroutineWorker` +
 * `EntryPointAccessors` is used here instead of `@HiltWorker`/`HiltWorkerFactory` (it would
 * require changing `BusEtTramApplication`'s class declaration, which is out of scope for this
 * task).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CacheRefreshHiltEntryPoint {
    fun transitNetworkRepository(): TransitNetworkRepository
}

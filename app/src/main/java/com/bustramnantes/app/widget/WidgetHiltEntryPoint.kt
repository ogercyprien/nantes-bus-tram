package com.bustramnantes.app.widget

import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.usecase.home.GetFavoritesWithDeparturesUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Manual Hilt entry point for the widget's `GlanceAppWidget`/`GlanceAppWidgetReceiver`/config
 * `Activity` classes.
 *
 * These classes are *not* annotated `@AndroidEntryPoint`/`@HiltWorker` on purpose: wiring
 * `GlanceAppWidget.provideGlance` (and `WidgetRefreshWorker`, see `worker/`) into Hilt's normal
 * injection would require `BusEtTramApplication` to implement `Configuration.Provider` and expose
 * a `HiltWorkerFactory` - a change to the *class declaration* of `BusEtTramApplication`, which is
 * out of scope for this task (only a single call from `onCreate()`'s body is allowed - see
 * [com.bustramnantes.app.worker.BackgroundWorkScheduler]). `EntryPointAccessors.fromApplication`
 * reaches into the existing `@HiltAndroidApp` component without touching `BusEtTramApplication`
 * at all, which is why it's used here instead. `FavoriteStopWidgetConfigActivity` *is* a normal
 * `@AndroidEntryPoint` Activity (that pattern needs no `Application` changes), so it injects
 * [FavoriteRepository] directly rather than through this entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetHiltEntryPoint {
    fun favoriteRepository(): FavoriteRepository
    fun getFavoritesWithDeparturesUseCase(): GetFavoritesWithDeparturesUseCase
}

package com.bustramnantes.app.worker

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.bustramnantes.app.widget.WidgetHiltEntryPoint
import dagger.hilt.android.EntryPointAccessors
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bustramnantes.app.widget.FavoriteStopWidget

/**
 * Refreshes every placed [FavoriteStopWidget] instance's departures roughly every 5 minutes,
 * with a `CONNECTED` network constraint applied by
 * [BackgroundWorkScheduler.scheduleNextWidgetRefresh].
 *
 * **Why this is a self-chaining `OneTimeWorkRequest`, not a `PeriodicWorkRequest`**:
 * WorkManager enforces a hard 15-minute floor on periodic work
 * (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`), which cannot express the "about 5
 * minutes" cadence this widget wants. The standard workaround - used here - is a unique
 * one-time work request that re-enqueues its own next run (with the same constraints) at the end
 * of [doWork], via [BackgroundWorkScheduler.scheduleNextWidgetRefresh].
 *
 * The chain only keeps itself alive while at least one [FavoriteStopWidget] instance is actually
 * placed on a home screen (`GlanceAppWidgetManager.getGlanceIds` is empty otherwise) - this
 * avoids waking the device every 5 minutes for a user who has never added the widget, or who has
 * removed their last instance. [com.bustramnantes.app.widget.FavoriteStopWidgetConfigActivity]
 * restarts the chain (idempotently) whenever a new instance is configured.
 *
 * [FavoriteStopWidget.updateAll] itself re-invokes `provideGlance` for every instance, which is
 * where the actual live-departures fetch happens (see that class) - this worker only decides
 * *whether* and *how often* that should occur, not what data to fetch.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val glanceIds = GlanceAppWidgetManager(applicationContext).getGlanceIds(FavoriteStopWidget::class.java)

        if (glanceIds.isNotEmpty()) {
            // A transient failure (e.g. offline despite the CONNECTED constraint having been
            // satisfied moments ago) should not permanently kill the refresh chain, so it's
            // swallowed here rather than propagated as Result.retry()/Result.failure().
            runCatching {
                // Warm the shared real-time cache first: ONE SIRI request covering every placed
                // widget's stop (waiting for the shared budget's next slot, bounded), so each
                // widget's provideGlance then reads fresh boards instead of provisional ones.
                val entryPoint = EntryPointAccessors.fromApplication(applicationContext, WidgetHiltEntryPoint::class.java)
                val favorites = glanceIds.mapNotNull { id ->
                    getAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id)[FavoriteStopWidget.FAVORITE_ID_KEY]
                        ?.let { entryPoint.favoriteRepository().getFavoriteById(it) }
                }
                if (favorites.isNotEmpty()) {
                    entryPoint.getFavoritesWithDeparturesUseCase()(favorites, awaitRealtimeMs = REALTIME_WAIT_MS)
                }
            }
            runCatching { FavoriteStopWidget().updateAll(applicationContext) }
            BackgroundWorkScheduler.scheduleNextWidgetRefresh(applicationContext)
        }

        return Result.success()
    }

    private companion object {
        /** Long enough to always reach the next SIRI slot (31 s budget), short enough for a worker. */
        const val REALTIME_WAIT_MS = 40_000L
    }
}

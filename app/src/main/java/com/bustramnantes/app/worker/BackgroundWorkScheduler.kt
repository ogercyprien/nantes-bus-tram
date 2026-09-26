package com.bustramnantes.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Single scheduling seam for both background tasks this module owns.
 *
 * **Integration contract**: the integration agent must call [schedule] exactly once from
 * `BusEtTramApplication.onCreate()` (a single call added to that method's *body* - this class
 * intentionally does not require any change to `BusEtTramApplication`'s class declaration, e.g.
 * no `Configuration.Provider`/`HiltWorkerFactory` wiring; see
 * [com.bustramnantes.app.widget.WidgetHiltEntryPoint]'s doc comment for why):
 *
 * ```kotlin
 * override fun onCreate() {
 *     super.onCreate()
 *     authRepository.warmUp() // wired by whoever owns that seam
 *     BackgroundWorkScheduler.schedule(this)
 * }
 * ```
 *
 * Both `enqueueUnique*` calls use [ExistingPeriodicWorkPolicy.KEEP]/[ExistingWorkPolicy.KEEP], so
 * calling [schedule] more than once (e.g. once from `Application.onCreate()` and again from
 * [com.bustramnantes.app.widget.FavoriteStopWidgetConfigActivity] whenever a widget instance is
 * configured) is always safe and never restarts an already-running chain/period.
 */
object BackgroundWorkScheduler {

    private const val DAILY_CACHE_REFRESH_WORK_NAME = "daily_cache_refresh"
    internal const val WIDGET_REFRESH_WORK_NAME = "widget_departure_refresh"
    private const val WIDGET_REFRESH_INTERVAL_MINUTES = 5L

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueues both the daily cache-refresh task and the widget-refresh chain. Idempotent. */
    fun schedule(context: Context) {
        scheduleDailyCacheRefresh(context)
        scheduleNextWidgetRefresh(context, initialDelayMinutes = 0, policy = ExistingWorkPolicy.KEEP)
    }

    private fun scheduleDailyCacheRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<CacheRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(connectedConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_CACHE_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueues the next iteration of [WidgetRefreshWorker]'s self-chain (see that class for why
     * this isn't a `PeriodicWorkRequest`). Called both from [schedule] (the very first link, with
     * [policy] = KEEP so a re-run of `Application.onCreate()` never duplicates an in-flight chain)
     * and from [WidgetRefreshWorker.doWork] itself (each subsequent link, [policy] = REPLACE since
     * the previous link has by definition just finished).
     */
    internal fun scheduleNextWidgetRefresh(
        context: Context,
        initialDelayMinutes: Long = WIDGET_REFRESH_INTERVAL_MINUTES,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(connectedConstraints)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WIDGET_REFRESH_WORK_NAME, policy, request)
    }
}

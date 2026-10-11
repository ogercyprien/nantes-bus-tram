package com.bustramnantes.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bustramnantes.app.domain.model.DataResult
import dagger.hilt.android.EntryPointAccessors

/**
 * Daily refresh of the static transit network: installs the current open-data GTFS publication when
 * it differs from the one in use (see
 * [com.bustramnantes.app.data.network.TransitNetworkRepository.refresh] - download only on an
 * unmetered network unless the installed feed is about to expire, on-device conversion, atomic swap).
 *
 * Scheduled by [BackgroundWorkScheduler.schedule] as a genuine `PeriodicWorkRequest` (a 1-day
 * interval is comfortably above WorkManager's 15-minute periodic-work floor, unlike the 5-minute
 * widget refresh - see [WidgetRefreshWorker]).
 */
class CacheRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, CacheRefreshHiltEntryPoint::class.java)
        return when (entryPoint.transitNetworkRepository().refresh(allowMetered = false)) {
            is DataResult.Success -> Result.success()
            is DataResult.Error -> Result.retry()
        }
    }
}

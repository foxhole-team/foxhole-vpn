package com.foxhole.vpn.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxhole.vpn.VpnApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Keep
class WidgetHomeRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!statusWidgetsPresent(applicationContext)) return Result.success()
        try {
            withTimeoutOrNull(12_000L) {
                (applicationContext as VpnApplication).runtimeController.refreshWidgetHomeLocation()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { }
        return Result.success()
    }
}

internal object WidgetHomeRefreshWork {
    private const val WorkName = "vpn-widget-home-refresh"

    fun request(context: Context) {
        if (!statusWidgetsPresent(context)) return
        val work = OneTimeWorkRequestBuilder<WidgetHomeRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE, work)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WorkName)
    }
}

internal fun statusWidgetsPresent(context: Context): Boolean = AppWidgetManager.getInstance(context)
    .getAppWidgetIds(ComponentName(context, StatusWidgetReceiver::class.java)).isNotEmpty()

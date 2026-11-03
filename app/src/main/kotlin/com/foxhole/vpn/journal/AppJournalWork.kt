package com.foxhole.vpn.journal

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

@Keep
class AppJournalMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if (AppJournal.shared(applicationContext).load()) Result.success() else Result.retry()
}

internal object AppJournalWork {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AppJournalMaintenanceWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "vpn-app-journal-retention", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

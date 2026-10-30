package com.foxhole.vpn.appupdates

import android.content.Context
import androidx.annotation.Keep
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxhole.vpn.BuildConfig
import com.foxhole.vpn.VpnApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@Keep
class VpnAppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!VpnAppUpdatePolicy.allowed(applicationContext.packageName, BuildConfig.APP_UPDATE_CHANNEL)) return Result.success()
        return try {
            val updater = (applicationContext as VpnApplication).appUpdater
            updater.restore()
            when (val result = updater.check(manual = false)) {
                is VpnAppUpdateCheck.Available -> { VpnAppUpdateNotifier(applicationContext).notify(result.release); Result.success() }
                VpnAppUpdateCheck.UpToDate, VpnAppUpdateCheck.NoRelease -> { VpnAppUpdateNotifier(applicationContext).clear(); Result.success() }
                VpnAppUpdateCheck.Disabled -> Result.success()
                is VpnAppUpdateCheck.Failed -> if (result.error == VpnAppUpdateError.NETWORK && runAttemptCount < 1) Result.retry() else Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 1) Result.retry() else Result.success()
        }
    }
}

internal object VpnAppUpdateWork {
    const val WORK_NAME = "vpn-app-update-check"

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!VpnAppUpdatePolicy.allowed(context.packageName, BuildConfig.APP_UPDATE_CHANNEL)) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<VpnAppUpdateWorker>(VpnAppUpdatePolicy.CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

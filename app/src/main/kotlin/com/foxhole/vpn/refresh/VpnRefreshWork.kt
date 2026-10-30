package com.foxhole.vpn.refresh

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
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.VpnSettings
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@Keep
class VpnSubscriptionRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(
    context,
    parameters
) {
    override suspend fun doWork(): Result = try {
        val controller = (applicationContext as VpnApplication).runtimeController
        val run = controller.refreshSubscriptionsAutomatically()
        if (run.retry && runAttemptCount < 2) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.success()
    }
}

@Keep
class VpnConnectionDataCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(
    context,
    parameters
) {
    override suspend fun doWork(): Result = try {
        val controller = (applicationContext as VpnApplication).runtimeController
        val retry = controller.checkConnectionDataAutomatically()
        if (retry && runAttemptCount < 2) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.success()
    }
}

internal class VpnRefreshWork(context: Context) {
    private val manager by lazy { WorkManager.getInstance(context.applicationContext) }
    private var configured: Configuration? = null

    @Synchronized
    fun apply(settings: VpnSettings, hasSubscriptions: Boolean) {
        val next = Configuration(
            settings.autoRefreshSubscriptions && hasSubscriptions,
            validHours(settings.subscriptionRefreshIntervalHours, 12),
            settings.autoCheckConnectionData,
            validHours(settings.connectionDataCheckIntervalHours, 24),
        )
        if (next == configured) return
        if (next.subscriptions) {
            val request = PeriodicWorkRequestBuilder<VpnSubscriptionRefreshWorker>(
                next.subscriptionHours.toLong(),
                TimeUnit.HOURS
            )
                .setInitialDelay(next.subscriptionHours.toLong(), TimeUnit.HOURS)
                .setConstraints(NetworkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(SubscriptionWork, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(SubscriptionWork)
        }
        if (next.connectionData) {
            val request = PeriodicWorkRequestBuilder<VpnConnectionDataCheckWorker>(
                next.dataHours.toLong(),
                TimeUnit.HOURS
            )
                .setInitialDelay(next.dataHours.toLong(), TimeUnit.HOURS)
                .setConstraints(NetworkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(ConnectionDataWork, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(ConnectionDataWork)
        }
        configured = next
    }

    private data class Configuration(val subscriptions: Boolean, val subscriptionHours: Int, val connectionData: Boolean, val dataHours: Int)

    private companion object {
        const val SubscriptionWork = "vpn-subscription-refresh"
        const val ConnectionDataWork = "vpn-connection-data-check"
        val NetworkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun validHours(value: Int, fallback: Int): Int = value.takeIf { it in 1..168 } ?: fallback
    }
}

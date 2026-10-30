package com.foxhole.vpn.refresh

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.VpnSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VpnRefreshWorkAndroidTest {
    @Test fun defaultsScheduleUniqueNetworkBoundTwelveAndTwentyFourHourJobs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as VpnApplication).runtimeController
        runBlocking { controller.awaitReady() }
        val scheduler = VpnRefreshWork(context)
        val manager = WorkManager.getInstance(context)
        val settings = VpnSettings()
        fun active(name: String): WorkInfo {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            do {
                val entries = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
                if (entries.size == 1) return entries.single()
                Thread.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            throw AssertionError("Expected one unique periodic refresh job")
        }
        try {
            scheduler.apply(settings, hasSubscriptions = true)
            val subscriptions = active("vpn-subscription-refresh")
            val data = active("vpn-connection-data-check")
            assertEquals(TimeUnit.HOURS.toMillis(12), subscriptions.periodicityInfo?.repeatIntervalMillis)
            assertEquals(TimeUnit.HOURS.toMillis(24), data.periodicityInfo?.repeatIntervalMillis)
            for (info in listOf(subscriptions, data)) {
                assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
                assertTrue(info.tags.all { !it.contains("://") })
            }
            scheduler.apply(settings, hasSubscriptions = true)
            assertEquals(subscriptions.id, active("vpn-subscription-refresh").id)
            assertEquals(data.id, active("vpn-connection-data-check").id)
        } finally {
            scheduler.apply(controller.settings.value, controller.state.value.profiles.any { it.refreshable })
        }
    }
}

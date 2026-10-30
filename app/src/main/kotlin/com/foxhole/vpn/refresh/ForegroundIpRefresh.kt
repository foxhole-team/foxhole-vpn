package com.foxhole.vpn.refresh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ForegroundIpRefresh(
    private val scope: CoroutineScope,
    private val refresh: () -> Unit,
    private val intervalMillis: Long = 30 * 60 * 1000L,
) {
    private var job: Job? = null

    @Synchronized
    fun setForeground(value: Boolean) {
        if (!value) {
            job?.cancel()
            job = null
        } else if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    delay(intervalMillis)
                    refresh()
                }
            }
        }
    }
}

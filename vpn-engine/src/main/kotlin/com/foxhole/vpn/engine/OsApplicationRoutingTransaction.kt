package com.foxhole.vpn.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class OsApplicationRoutingTransaction(
    private val isCurrent: () -> Boolean,
    private val holdNative: suspend () -> Unit,
    private val establishBarrier: suspend () -> Unit,
    private val stopPrevious: suspend () -> Unit,
    private val establishTarget: suspend () -> Unit,
    private val releaseAndValidate: suspend () -> EngineLocation?,
    private val retireTarget: suspend () -> Unit,
) {
    suspend fun apply(persist: suspend () -> Unit): EngineLocation? {
        fun owned() { check(isCurrent()) { "Routing handover superseded" } }
        owned()
        try {
            holdNative()
            owned()
            establishBarrier()
            owned()
            stopPrevious()
            owned()
            persist()
            owned()
            establishTarget()
            owned()
            val location = releaseAndValidate()
            owned()
            return location
        } catch (failure: Throwable) {
            if (isCurrent()) withContext(NonCancellable) {
                // A target establish deactivates the previous barrier. Only a fresh acknowledged barrier permits target close.
                establishBarrier()
                retireTarget()
            }
            throw failure
        }
    }
}

package com.foxhole.vpn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun <T> commitFencedProfileChoice(
    before: T,
    candidate: T,
    write: suspend ((T) -> T) -> T,
    stillCurrent: () -> Boolean,
    rollbackFailed: () -> Unit = {},
    publishIfCurrent: (T) -> Boolean,
) = withContext(NonCancellable) {
    if (!stillCurrent() || candidate == before) return@withContext
    val next = write { current ->
        check(current == before) { "Profile store changed outside its mutation owner" }
        if (stillCurrent()) candidate else current
    }
    if (next != before) {
        if (!publishIfCurrent(next)) {
            try {
                write { current ->
                    check(current == next) { "Profile store changed before choice rollback" }
                    before
                }
            } catch (failure: Throwable) {
                rollbackFailed()
                throw failure
            }
        }
    }
}

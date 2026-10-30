package com.foxhole.vpn.engine

import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class ValidationFailure {
    TUN_CLOSED, NETWORK_MISSING, NATIVE_STATS_OVERSIZED, NATIVE_STATS_MALFORMED,
    NATIVE_GENERATION_MISSING, ROUTE_CHANGED, HTTP_STATUS, DNS, SOCKET, TLS,
    TIMEOUT, IO, ENGINE_REJECTED, UNEXPECTED,
}

internal class ValidationException(val failure: ValidationFailure) : IllegalStateException(failure.name)

internal fun classifyValidationFailure(failure: Throwable?): ValidationFailure = when (failure) {
    is ValidationException -> failure.failure
    is TimeoutCancellationException, is SocketTimeoutException -> ValidationFailure.TIMEOUT
    is UnknownHostException -> ValidationFailure.DNS
    is SSLException -> ValidationFailure.TLS
    is SocketException -> ValidationFailure.SOCKET
    is IOException -> ValidationFailure.IO
    is EngineException -> ValidationFailure.ENGINE_REJECTED
    else -> ValidationFailure.UNEXPECTED
}

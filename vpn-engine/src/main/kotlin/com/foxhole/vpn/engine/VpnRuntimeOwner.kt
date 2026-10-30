package com.foxhole.vpn.engine

import com.foxhole.vpn.profile.VpnProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class EnginePhase { IDLE, CONNECTING, VALIDATING, CONNECTED, STOPPING, ERROR }
enum class EngineFailure { START, VALIDATION, NATIVE_UNAVAILABLE, PROFILE, STOP, GEO }
class EngineException(val reason: EngineFailure, val processPoisoned: Boolean = false) : IllegalStateException(reason.name)
data class EngineLocation(
    val countryCode: String,
    val city: String,
    val country: String,
    val ip: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
data class EngineSnapshot(
    val phase: EnginePhase = EnginePhase.IDLE,
    val profileId: String? = null,
    val location: EngineLocation? = null,
    val failure: EngineFailure? = null,
    val hasOwnedTunnel: Boolean = false,
    val validationFailureCode: String? = null,
    val commandTicket: Long? = null,
    val validatedNetworkHandle: Long? = null,
)
data class EngineStopResult(val released: Boolean, val poisoned: Boolean = false)

interface EngineDriver {
    suspend fun open(profile: VpnProfile): EngineSession
    fun abortPendingTun()
}

interface EngineSession {
    val ownedNetworkHandle: Long? get() = null
    suspend fun validate(): EngineLocation?
    suspend fun refreshLocation(): EngineLocation
    suspend fun networkChanged()
    suspend fun checkHealth(): Boolean
    suspend fun stop(): EngineStopResult
}

class VpnRuntimeOwner(
    private val scope: CoroutineScope,
    private val onIdle: (EngineDriver) -> Unit,
    private val onPoisoned: (EngineDriver) -> Unit,
) {
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(EngineSnapshot())
    val state: StateFlow<EngineSnapshot> = mutable
    private var generation = 0L
    private var lastAcceptedTicket: Long? = null
    private var probeGeneration = 0L
    private var desired: Request? = null
    private var pending: Request? = null
    private var active: Active? = null
    private var stopping = false
    private var poisoned = false
    private var finalFailure: EngineFailure? = null
    private var lastValidationFailure: ValidationFailure? = null
    private var probe: Job? = null
    private var health: Job? = null

    init {
        scope.launch {
            for (event in events) {
                when (event) {
                    is Event.Connect -> connectNow(event.profile, event.driver, event.commandTicket)
                    is Event.Stop -> stopNow(event.driver)
                    is Event.Opened -> opened(event)
                    is Event.Validated -> validated(event)
                    is Event.Stopped -> stopped(event)
                    is Event.Geo -> updateGeo(event)
                    is Event.PendingExpired -> expirePending(event.request)
                    is Event.Health -> healthChecked(event)
                    Event.Refresh -> refreshNow()
                    Event.NetworkChanged -> revalidateNow()
                }
            }
        }
    }

    fun connect(profile: VpnProfile, driver: EngineDriver, commandTicket: Long? = null) {
        events.trySend(Event.Connect(profile, driver, commandTicket))
    }
    fun stop(driver: EngineDriver? = null) { events.trySend(Event.Stop(driver)) }
    fun refreshLocation() { events.trySend(Event.Refresh) }
    fun networkChanged() { events.trySend(Event.NetworkChanged) }

    private fun connectNow(profile: VpnProfile, driver: EngineDriver, commandTicket: Long?) {
        if (poisoned) return
        if (commandTicket != null && (
                desired?.commandTicket == commandTicket || active?.request?.commandTicket == commandTicket ||
                    pending?.commandTicket == commandTicket ||
                    (mutable.value.phase == EnginePhase.ERROR && lastAcceptedTicket == commandTicket)
                )
        ) return
        if (desired?.profile == profile && desired?.driver === driver && desired?.commandTicket == commandTicket &&
            mutable.value.phase != EnginePhase.ERROR
        ) return
        desired = Request(++generation, profile, driver, commandTicket)
        finalFailure = null
        lastValidationFailure = null
        probe?.cancel()
        health?.cancel()
        probeGeneration++
        if (active != null) stopActive() else if (pending == null) startDesired()
        else publish(EngineSnapshot(EnginePhase.STOPPING, profile.id))
    }

    private fun stopNow(driver: EngineDriver?) {
        if (poisoned) return
        if (driver != null && active?.request?.driver !== driver && pending?.driver !== driver && desired?.driver !== driver) return
        desired = null
        generation++
        finalFailure = null
        lastValidationFailure = null
        probe?.cancel()
        health?.cancel()
        probeGeneration++
        when {
            active != null -> stopActive()
            pending != null -> {
                val request = checkNotNull(pending)
                request.driver.abortPendingTun()
                publish(EngineSnapshot(EnginePhase.STOPPING, request.profile.id))
                scope.launch { delay(5_000); events.send(Event.PendingExpired(request)) }
            }
            else -> {
                publish(EngineSnapshot())
                driver?.let(onIdle)
            }
        }
    }

    private fun startDesired() {
        val request = desired ?: return
        if (poisoned || stopping || pending != null || active != null) return
        pending = request
        lastAcceptedTicket = request.commandTicket
        publish(EngineSnapshot(EnginePhase.CONNECTING, request.profile.id))
        scope.launch {
            val result = captureEngineResult { request.driver.open(request.profile) }
            events.send(Event.Opened(request, result))
        }
    }

    private fun opened(event: Event.Opened) {
        if (pending !== event.request) return
        pending = null
        val session = event.result.getOrNull()
        if (session == null) {
            event.request.driver.abortPendingTun()
            val error = event.result.exceptionOrNull() as? EngineException
            if (error?.processPoisoned == true) {
                poisoned = true
                desired = null
                if (error.reason == EngineFailure.VALIDATION) {
                    lastValidationFailure = classifyValidationFailure(error)
                }
                publish(EngineSnapshot(EnginePhase.ERROR, event.request.profile.id, failure = error.reason))
                onPoisoned(event.request.driver)
                return
            }
            if (desired === event.request) {
                desired = null
                if (error?.reason == EngineFailure.VALIDATION) {
                    lastValidationFailure = classifyValidationFailure(error)
                }
                publish(EngineSnapshot(
                    EnginePhase.ERROR, event.request.profile.id,
                    failure = (event.result.exceptionOrNull() as? EngineException)?.reason ?: EngineFailure.START,
                ))
            } else if (desired != null) startDesired() else publish(EngineSnapshot())
            releaseDriver(event.request.driver)
            return
        }
        active = Active(event.request, session)
        if (desired !== event.request || generation != event.request.generation) stopActive()
        else validateActive(rebind = false)
    }

    private fun validateActive(rebind: Boolean) {
        val captured = active ?: return
        val version = ++probeGeneration
        probe?.cancel()
        health?.cancel()
        publish(EngineSnapshot(EnginePhase.VALIDATING, captured.request.profile.id))
        probe = scope.launch {
            val result = captureEngineResult {
                if (rebind) captured.session.networkChanged()
                captured.session.validate()
            }
            events.send(Event.Validated(captured, version, result))
        }
    }

    private fun validated(event: Event.Validated) {
        if (active !== event.active || desired !== event.active.request || event.version != probeGeneration || stopping) return
        lastValidationFailure = event.result.exceptionOrNull()?.let(::classifyValidationFailure)
        if (event.result.isSuccess) {
            event.active.validated = true
            publish(EngineSnapshot(EnginePhase.CONNECTED, event.active.request.profile.id, event.result.getOrNull()))
            scheduleHealth(event.active)
        } else if (event.active.validated) {
            publish(EngineSnapshot(EnginePhase.ERROR, event.active.request.profile.id, failure = EngineFailure.VALIDATION))
            scheduleHealth(event.active)
        } else {
            desired = null
            finalFailure = EngineFailure.VALIDATION
            stopActive()
        }
    }

    private fun stopActive() {
        val captured = active ?: return
        if (stopping) return
        stopping = true
        probe?.cancel()
        health?.cancel()
        probeGeneration++
        publish(EngineSnapshot(EnginePhase.STOPPING, captured.request.profile.id))
        scope.launch {
            val result = captureEngineResult { captured.session.stop() }
                .getOrDefault(EngineStopResult(released = false, poisoned = true))
            events.send(Event.Stopped(captured, result))
        }
    }

    private fun stopped(event: Event.Stopped) {
        if (active !== event.active) return
        stopping = false
        if (!event.result.released) {
            desired = null
            publish(EngineSnapshot(EnginePhase.ERROR, event.active.request.profile.id, failure = EngineFailure.STOP))
            if (event.result.poisoned) {
                poisoned = true
                onPoisoned(event.active.request.driver)
            }
            return
        }
        active = null
        if (desired != null) startDesired()
        else publish(when {
            poisoned -> EngineSnapshot(EnginePhase.ERROR, failure = EngineFailure.STOP)
            finalFailure != null -> EngineSnapshot(EnginePhase.ERROR, failure = finalFailure)
            else -> EngineSnapshot()
        })
        releaseDriver(event.active.request.driver)
    }

    private fun expirePending(request: Request) {
        if (pending !== request || desired === request) return
        poisoned = true
        publish(EngineSnapshot(EnginePhase.ERROR, request.profile.id, failure = EngineFailure.STOP))
        onPoisoned(request.driver)
    }

    private fun refreshNow() {
        val captured = active ?: return
        if (mutable.value.phase != EnginePhase.CONNECTED || stopping) return
        val version = ++probeGeneration
        probe?.cancel()
        health?.cancel()
        probe = scope.launch {
            events.send(Event.Geo(captured, version, captureEngineResult { captured.session.refreshLocation() }))
        }
    }

    private fun updateGeo(event: Event.Geo) {
        if (active !== event.active || desired !== event.active.request || probeGeneration != event.version || stopping) return
        publish(mutable.value.copy(location = event.result.getOrNull(), failure = if (event.result.isFailure) EngineFailure.GEO else null))
        scheduleHealth(event.active)
    }

    private fun revalidateNow() {
        if (active != null && desired === active?.request && !stopping) validateActive(rebind = true)
    }

    private fun scheduleHealth(captured: Active) {
        health?.cancel()
        val version = probeGeneration
        health = scope.launch {
            delay(10_000)
            events.send(Event.Health(captured, version, captureEngineResult { captured.session.checkHealth() }))
        }
    }

    private fun healthChecked(event: Event.Health) {
        if (active !== event.active || desired !== event.active.request || event.version != probeGeneration || stopping || poisoned) return
        if (event.result.isFailure) {
            lastValidationFailure = classifyValidationFailure(event.result.exceptionOrNull())
            publish(EngineSnapshot(EnginePhase.ERROR, event.active.request.profile.id, failure = EngineFailure.VALIDATION))
            scheduleHealth(event.active)
        } else if (event.result.getOrNull() == true || mutable.value.phase == EnginePhase.ERROR) {
            validateActive(rebind = false)
        } else {
            scheduleHealth(event.active)
        }
    }

    private fun publish(snapshot: EngineSnapshot) {
        val ticket = when (snapshot.phase) {
            EnginePhase.CONNECTED, EnginePhase.VALIDATING -> active?.request?.commandTicket
            EnginePhase.CONNECTING -> (pending ?: desired)?.commandTicket
            EnginePhase.STOPPING -> (active?.request ?: pending ?: desired)?.commandTicket
            EnginePhase.ERROR -> {
                val owner = active?.request ?: pending
                if (owner != null) owner.commandTicket else lastAcceptedTicket
            }
            EnginePhase.IDLE -> null
        }
        if (snapshot.phase == EnginePhase.IDLE) lastAcceptedTicket = null
        mutable.value = snapshot.copy(
            hasOwnedTunnel = active != null,
            location = snapshot.location.takeIf { snapshot.phase == EnginePhase.CONNECTED },
            validationFailureCode = if (snapshot.failure == EngineFailure.VALIDATION) lastValidationFailure?.name else null,
            commandTicket = ticket,
            validatedNetworkHandle = active?.takeIf { it.validated && snapshot.phase == EnginePhase.CONNECTED }?.session?.ownedNetworkHandle,
        )
    }

    private fun releaseDriver(driver: EngineDriver) {
        if (desired?.driver !== driver && pending?.driver !== driver && active?.request?.driver !== driver) onIdle(driver)
    }

    private data class Request(val generation: Long, val profile: VpnProfile, val driver: EngineDriver, val commandTicket: Long?)
    private data class Active(val request: Request, val session: EngineSession, var validated: Boolean = false)
    private sealed interface Event {
        data class Connect(val profile: VpnProfile, val driver: EngineDriver, val commandTicket: Long?) : Event
        data class Stop(val driver: EngineDriver?) : Event
        data class Opened(val request: Request, val result: Result<EngineSession>) : Event
        data class Validated(val active: Active, val version: Long, val result: Result<EngineLocation?>) : Event
        data class Stopped(val active: Active, val result: EngineStopResult) : Event
        data class Geo(val active: Active, val version: Long, val result: Result<EngineLocation>) : Event
        data class PendingExpired(val request: Request) : Event
        data class Health(val active: Active, val version: Long, val result: Result<Boolean>) : Event
        data object Refresh : Event
        data object NetworkChanged : Event
    }
}

private suspend inline fun <T> captureEngineResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    // A nested native deadline must settle this request; owner cancellation still propagates.
    currentCoroutineContext().ensureActive()
    Result.failure(cancelled)
} catch (failure: Exception) {
    Result.failure(failure)
}

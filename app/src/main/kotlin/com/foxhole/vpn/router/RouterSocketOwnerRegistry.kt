package com.foxhole.vpn.router

import java.net.Socket
import java.util.WeakHashMap

internal class RouterSocketOwnerSnapshot<Service : Any, Driver : Any>(
    val service: Service,
    val driver: Driver,
    val ticket: Long,
    val commandGeneration: Long,
) {
    fun sameOwner(other: RouterSocketOwnerSnapshot<Service, Driver>): Boolean = service === other.service &&
        driver === other.driver && ticket == other.ticket && commandGeneration == other.commandGeneration
}

internal class RouterSocketOwnerRegistry<Service : Any, Driver : Any>(
    private val lock: Any,
    private val capture: () -> RouterSocketOwnerSnapshot<Service, Driver>?,
    private val protect: (RouterSocketOwnerSnapshot<Service, Driver>, Socket) -> Boolean,
) {
    private val approved = WeakHashMap<Socket, RouterSocketOwnerSnapshot<Service, Driver>>()

    fun protect(socket: Socket): Boolean {
        val owner = synchronized(lock) {
            approved.remove(socket)
            if (socket.isClosed) return false
            capture() ?: return false
        }
        if (!protect(owner, socket)) return false
        return synchronized(lock) {
            approved.entries.removeAll { it.key.isClosed }
            if (socket.isClosed || capture()?.let(owner::sameOwner) != true || approved.size >= 128) false
            else { approved[socket] = owner; true }
        }
    }

    fun isCurrent(socket: Socket): Boolean = synchronized(lock) {
        val owner = approved[socket] ?: return false
        val valid = !socket.isClosed && capture()?.let(owner::sameOwner) == true
        if (!valid) approved.remove(socket)
        valid
    }
}

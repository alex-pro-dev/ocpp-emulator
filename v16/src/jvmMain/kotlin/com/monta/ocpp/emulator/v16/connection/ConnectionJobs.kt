package com.monta.ocpp.emulator.v16.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Orders commands per charger; a connection owns its handshake, retries and session children. */
internal class ConnectionJobs(
    private val scope: CoroutineScope,
    private val runConnection: suspend (Long) -> Unit,
    private val stopConnection: suspend (Long) -> Unit,
) {
    private class Slot {
        var command: Job? = null
        var connection: Job? = null
    }

    private val slots = mutableMapOf<Long, Slot>()
    private var shuttingDown = false

    fun connect(
        id: Long,
    ): Job = enqueue(id) { slot ->
        // Connecting and backing off count as owned, even when connected=false in the DB.
        if (slot.connection?.isActive != true) {
            // A cancelled job may still be releasing its socket and protocol session.
            slot.connection?.join()
            slot.connection = scope.launch { runConnection(id) }
        }
    }

    fun disconnect(
        id: Long,
    ): Job = enqueue(id) { slot -> stop(id, slot) }

    fun reconnect(
        id: Long,
        delayInSeconds: Int,
    ): Job = enqueue(id) { slot ->
        if (slot.connection == null) return@enqueue
        stop(id, slot)
        slot.connection = scope.launch {
            delay(delayInSeconds.coerceAtLeast(0).toLong() * 1000)
            runConnection(id)
        }
    }

    suspend fun disconnectAll() {
        val commands = synchronized(slots) {
            shuttingDown = true
            slots.map { (id, slot) -> queue(slot) { stop(id, slot) } }
        }
        commands.joinAll()
    }

    private fun enqueue(
        id: Long,
        action: suspend (Slot) -> Unit,
    ): Job = synchronized(slots) {
        if (shuttingDown) return@synchronized Job().apply { complete() }
        val slot = slots.getOrPut(id) { Slot() }
        queue(slot) { action(slot) }
    }

    private fun queue(
        slot: Slot,
        action: suspend () -> Unit,
    ): Job {
        val previous = slot.command
        return scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            action()
        }.also {
            slot.command = it
            it.start()
        }
    }

    private suspend fun stop(
        id: Long,
        slot: Slot,
    ) {
        val connection = slot.connection ?: return
        try {
            if (connection.isActive) stopConnection(id)
        } finally {
            // Do not let old cleanup disconnect the replacement's protocol session.
            connection.cancelAndJoin()
            slot.connection = null
        }
    }
}

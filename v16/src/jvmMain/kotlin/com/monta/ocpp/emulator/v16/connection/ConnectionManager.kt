package com.monta.ocpp.emulator.v16.connection

import com.monta.ocpp.emulator.chargepoint.repository.ChargePointRepository
import com.monta.ocpp.emulator.interceptor.MessageInterceptor
import com.monta.ocpp.emulator.v16.SchedulerService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

@Singleton
class ConnectionManager(
    val messageInterceptor: MessageInterceptor,
    val chargePointRepository: ChargePointRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val connections = ConcurrentHashMap<Long, ChargePointConnection>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, exception ->
            logger.error(exception) { "Charge point connection task failed" }
        },
    )
    private val jobs = ConnectionJobs(
        scope = scope,
        runConnection = { id ->
            logger.info { "Connecting chargePointId=$id" }
            chargePointRepository.clearChargePointBootStatus(id)
            messageInterceptor.getConfig(id)
            val connection = ChargePointConnection(id)
            val scheduler = SchedulerService(id)
            connections[id] = connection
            try {
                coroutineScope {
                    scheduler.start(this)
                    try {
                        connection.connect()
                    } finally {
                        withContext(NonCancellable) { scheduler.stopAndJoin() }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    scheduler.stopAndJoin()
                    connections.remove(id, connection)
                }
            }
        },
        stopConnection = { id ->
            logger.info { "Disconnecting chargePointId=$id" }
            // A silent peer must not hold up cancellation or app shutdown for a full CALL timeout.
            withTimeoutOrNull(2_000) { connections[id]?.disconnect() }
        },
    )

    fun connect(
        chargePointId: Long,
    ) {
        jobs.connect(chargePointId)
    }

    suspend fun disconnectAll() {
        jobs.disconnectAll()
    }

    fun disconnect(
        chargePointId: Long,
    ): Job = jobs.disconnect(chargePointId)

    fun reconnect(
        chargePointId: Long,
        delayInSeconds: Int,
    ) {
        jobs.reconnect(chargePointId, delayInSeconds)
    }
}

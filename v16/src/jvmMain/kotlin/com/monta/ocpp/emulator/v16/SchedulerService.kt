package com.monta.ocpp.emulator.v16

import com.monta.library.ocpp.v16.client.OcppClientV16
import com.monta.library.ocpp.v16.core.MeterValue
import com.monta.library.ocpp.v16.core.MeterValuesRequest
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import com.monta.ocpp.emulator.common.util.TransactionEnergyMeter
import com.monta.ocpp.emulator.common.util.injectAnywhere
import com.monta.ocpp.emulator.logger.GlobalLogger
import com.monta.ocpp.emulator.v16.data.service.EffectiveChargingLimit
import com.monta.ocpp.emulator.v16.data.service.TxDefaultService
import com.monta.ocpp.emulator.v16.util.MeterValuesGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.annotation.Factory
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

@Factory
class SchedulerService(
    private val chargePointId: Long,
) {

    private val logger = KotlinLogging.logger {}
    private val ocppClientV16: OcppClientV16 by injectAnywhere()
    private val chargePointService: ChargePointService by injectAnywhere()
    private val txDefaultService: TxDefaultService by injectAnywhere()

    private val chargePoint: ChargePointDAO
        get() = chargePointService.getById(chargePointId)

    private val heartbeatInterval: Long
        get() = chargePoint.configuration.heartbeatInterval

    private val meterValueSampleInterval: Long
        get() = chargePoint.configuration.meterValueSampleInterval

    private val meterValuesSampledData: List<String>
        get() = chargePoint.configuration.meterValuesSampledData

    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
    ) {
        logger.info { "starting scheduler" }
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(1000)
                yield()
                if (!chargePoint.connected) {
                    continue
                }
                try {
                    heartbeat()
                    handleActiveTransactions()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    logger.error(exception) { "Scheduler iteration failed for chargePointId=$chargePointId" }
                }
            }
        }
    }

    fun stop() {
        logger.info { "stopping scheduler" }
        job?.cancel()
    }

    suspend fun stopAndJoin() {
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun heartbeat() {
        if (heartbeatInterval == 0L) {
            logger.trace { "heartbeat disabled" }
            return
        }

        val elapsedTime = Duration.between(
            chargePoint.heartbeatAt,
            Instant.now(),
        )

        if (elapsedTime.seconds < heartbeatInterval) {
            return
        }

        try {
            val ocppSession = ocppClientV16.getSessionByIdentity(chargePoint.identity)
            ocppClientV16.asCoreProfile(ocppSession.info).heartbeat()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(exception) { "failed to send heartbeat" }
            GlobalLogger.warn(chargePoint, "Failed to send heartbeat")
        }

        transaction {
            chargePoint.heartbeatAt = Instant.now()
        }
    }

    private suspend fun handleActiveTransactions() {
        val sampledAt = Instant.now()
        val monotonicNanos = System.nanoTime()
        val limits = txDefaultService.refreshChargingLimits(chargePoint, sampledAt)
        val transactions = transaction { chargePoint.getActiveTransactions() }
        for (transaction in transactions) {
            try {
                handleTransaction(transaction, limits[transaction.connectorPosition], sampledAt, monotonicNanos)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(exception) { "failed to update transaction id=${transaction.id}" }
            }
        }
    }

    private suspend fun handleTransaction(
        transaction: ChargePointTransactionDAO,
        limit: EffectiveChargingLimit?,
        sampledAt: Instant,
        monotonicNanos: Long,
    ) {
        val connector = transaction {
            transaction.chargePointConnector
        }

        connector.setStatus(connector.calculateState())
        TransactionEnergyMeter.advance(transaction, limit?.watts ?: 0.0, sampledAt, monotonicNanos)

        sendMeterValues(transaction, limit?.watts ?: 0.0, limit?.numberPhases ?: connector.vehicleNumberPhases)
    }

    private suspend fun sendMeterValues(
        transaction: ChargePointTransactionDAO,
        watts: Double,
        vehicleNumberPhases: Int,
    ) {
        if (meterValueSampleInterval == 0L) {
            logger.trace { "meterValues disabled" }
            return
        }

        val secondsSinceMeterValues = Duration.between(
            transaction.meterValuesAt,
            Instant.now(),
        ).toSeconds()

        if (secondsSinceMeterValues < meterValueSampleInterval) {
            return
        }

        val ocppSession = ocppClientV16.getSessionByIdentity(chargePoint.identity)

        ocppClientV16.asCoreProfile(ocppSession.info).meterValues(
            MeterValuesRequest(
                connectorId = transaction.connectorPosition(),
                transactionId = transaction.externalId,
                meterValue = listOf(
                    MeterValue(
                        timestamp = ZonedDateTime.now(),
                        sampledValue = MeterValuesGenerator.generate(
                            meterValuesSampledData = meterValuesSampledData,
                            startTime = transaction.startTime,
                            endMeter = transaction.endMeter,
                            watts = watts,
                            numberPhases = vehicleNumberPhases,
                            meterType = chargePoint.meterType,
                        ),
                    ),
                ),
            ),
        )

        transaction {
            transaction.meterValuesAt = Instant.now()
        }

        GlobalLogger.info(transaction, "Meter values sent")
    }
}

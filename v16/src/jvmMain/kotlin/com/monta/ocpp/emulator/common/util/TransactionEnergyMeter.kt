package com.monta.ocpp.emulator.common.util

import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransaction
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant

/** Fractional Wh are persisted in the existing double meter column. */
object TransactionEnergyMeter {
    private data class Sample(val nanos: Long, val wattHours: Double, val at: Instant)
    private val clocks = mutableMapOf<Long, Sample>()

    fun wattHours(
        watts: Double,
        elapsed: Duration,
    ): Double {
        if (!watts.isFinite() || watts <= 0.0 || elapsed.isNegative) return 0.0
        return watts * (elapsed.seconds.toDouble() + elapsed.nano / 1_000_000_000.0) / 3600.0
    }

    /** Scheduler and triggered reads share one clock, so neither can count an interval twice. */
    fun advance(
        session: ChargePointTransactionDAO,
        watts: Double,
        at: Instant = Instant.now(),
        monotonicNanos: Long = System.nanoTime(),
    ): Double = synchronized(clocks) {
        transaction {
            val persisted = ChargePointTransaction.selectAll().where { ChargePointTransaction.id eq session.id }.single()
            val previous = clocks[session.id.value]
            session.endMeter = maxOf(session.endMeter, persisted[ChargePointTransaction.endMeter], previous?.wattHours ?: session.endMeter)
            session.endMeterAt = maxOf(session.endMeterAt, previous?.at ?: session.endMeterAt)
            if (persisted[ChargePointTransaction.endTime] != null) {
                clocks.remove(session.id.value)
                return@transaction session.endMeter
            }
            val elapsed = if (previous == null) {
                Duration.between(session.endMeterAt, at).coerceAtLeast(Duration.ZERO)
            } else {
                val nanos = monotonicNanos - previous.nanos
                if (nanos <= 0) return@transaction session.endMeter
                Duration.ofNanos(nanos)
            }
            session.endMeter += wattHours(watts, elapsed)
            // A wall-clock correction cannot rewind the persisted checkpoint.
            session.endMeterAt = maxOf(session.endMeterAt, previous?.at ?: session.endMeterAt, at)
            if (session.chargePointConnector.meterAt?.let { at > it } != false) session.chargePointConnector.meterAt = at
            clocks[session.id.value] = Sample(monotonicNanos, session.endMeter, session.endMeterAt)
            session.endMeter
        }
    }

    fun forget(
        session: ChargePointTransactionDAO,
    ) {
        synchronized(clocks) { clocks.remove(session.id.value) }
    }
}

package com.monta.ocpp.emulator.v16

import com.monta.library.ocpp.v16.core.Reason
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import com.monta.ocpp.emulator.common.util.TransactionEnergyMeter
import com.monta.ocpp.emulator.common.util.injectAnywhere
import com.monta.ocpp.emulator.logger.GlobalLogger
import com.monta.ocpp.emulator.v16.data.service.TxDefaultService
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

suspend fun ChargePointTransactionDAO.stop(
    reason: Reason?,
    endReasonDescription: String? = null,
) {
    val connector = transaction { chargePointConnector }

    GlobalLogger.info(this, "Attempting to stop charge")

    val profileService: TxDefaultService by injectAnywhere()
    val limits = profileService.calculateChargingLimits(connector.getChargePoint())
    TransactionEnergyMeter.advance(this, limits[connector.position]?.watts ?: 0.0)

    stopTransaction(
        sessionInfo = connector.sessionInfo,
        transaction = this,
        reason = reason,
    )

    transaction {
        // Set connector values
        connector.activeTransaction = null
        // Set transaction values
        this@stop.endTime = Instant.now()
        this@stop.endReason = reason
        this@stop.endReasonDescription = endReasonDescription
        this@stop.chargingProfile = null
        val profileService: TxDefaultService by injectAnywhere()
        profileService.deleteTransactionProfiles(connector.getChargePoint(), externalId)
    }

    TransactionEnergyMeter.forget(this)
    profileService.refreshChargingLimits(connector.getChargePoint())

    GlobalLogger.info(connector, "Charge was stopped")

    connector.setStatuses(
        connector.calculateState(true),
    )
}

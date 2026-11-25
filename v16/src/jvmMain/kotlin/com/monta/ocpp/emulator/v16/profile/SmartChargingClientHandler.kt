package com.monta.ocpp.emulator.v16.profile

import com.monta.library.ocpp.common.chargingprofile.ChargingRateUnit
import com.monta.library.ocpp.common.session.OcppSession
import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileConfirmation
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileRequest
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileStatus
import com.monta.library.ocpp.v16.smartcharge.GetCompositeScheduleConfirmation
import com.monta.library.ocpp.v16.smartcharge.GetCompositeScheduleRequest
import com.monta.library.ocpp.v16.smartcharge.GetCompositeScheduleStatus
import com.monta.library.ocpp.v16.smartcharge.SetChargingProfileConfirmation
import com.monta.library.ocpp.v16.smartcharge.SetChargingProfileRequest
import com.monta.library.ocpp.v16.smartcharge.SetChargingProfileStatus
import com.monta.library.ocpp.v16.smartcharge.SmartChargeClientProfile
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.chargepointconnector.entity.ChargePointConnectorDAO
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransaction
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import com.monta.ocpp.emulator.chargepointtransaction.service.ChargePointTransactionService
import com.monta.ocpp.emulator.common.util.ChargingProfileCalculator
import com.monta.ocpp.emulator.logger.GlobalLogger
import com.monta.ocpp.emulator.v16.data.repository.StoredChargingProfile
import com.monta.ocpp.emulator.v16.data.repository.TxDefaultRepository
import com.monta.ocpp.emulator.v16.data.service.TxDefaultService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import javax.inject.Singleton

@Singleton
class SmartChargingClientHandler(
    private val chargePointService: ChargePointService,
    private val chargePointTransactionService: ChargePointTransactionService,
    private val txDefaultService: TxDefaultService,
) : SmartChargeClientProfile.Listener {

    override suspend fun clearChargingProfile(
        ocppSessionInfo: OcppSession.Info,
        request: ClearChargingProfileRequest,
    ): ClearChargingProfileConfirmation {
        return clearChargingProfile(ocppSessionInfo, request, null)
    }

    suspend fun clearChargingProfile(
        ocppSessionInfo: OcppSession.Info,
        request: ClearChargingProfileRequest,
        stackLevel: Int?,
    ): ClearChargingProfileConfirmation {
        val chargePoint = chargePointService.getByIdentity(ocppSessionInfo.identity)
        val connector = request.connectorId?.let { position ->
            transaction { chargePoint.connectors.firstOrNull { it.position == position } }
        }
        if (request.connectorId != null && connector == null) {
            return ClearChargingProfileConfirmation(status = ClearChargingProfileStatus.Unknown)
        }
        val deleted = transaction {
            var count = txDefaultService.clear(chargePoint, connector, request, stackLevel)
            ChargePointTransactionDAO.find { ChargePointTransaction.chargePointId eq chargePoint.id }
                .filter { connector == null || it.chargePointConnector.id == connector.id }
                .forEach { legacyTransaction ->
                    val legacy = legacyTransaction.chargingProfile
                    if (legacy != null && TxDefaultRepository.matches(legacy, request, stackLevel)) {
                        legacyTransaction.chargingProfile = null
                        count += 1
                    }
                }
            count
        }
        if (deleted > 0) txDefaultService.refreshChargingLimits(chargePoint)
        return ClearChargingProfileConfirmation(
            status = if (deleted > 0) ClearChargingProfileStatus.Accepted else ClearChargingProfileStatus.Unknown,
        )
    }

    override suspend fun getCompositeSchedule(
        ocppSessionInfo: OcppSession.Info,
        request: GetCompositeScheduleRequest,
    ): GetCompositeScheduleConfirmation {
        val chargePoint = chargePointService.getByIdentity(ocppSessionInfo.identity)
        val connectorId = request.connectorId
        val duration = request.duration
        val unit = request.chargingRateUnit ?: ChargingRateUnit.W
        return try {
            val schedule = txDefaultService.compositeSchedule(chargePoint, connectorId, duration, unit)
            GetCompositeScheduleConfirmation(
                status = GetCompositeScheduleStatus.Accepted,
                connectorId = connectorId,
                scheduleStart = schedule.startSchedule,
                chargingSchedule = schedule,
            )
        } catch (_: IllegalArgumentException) {
            GetCompositeScheduleConfirmation(status = GetCompositeScheduleStatus.Rejected)
        }
    }

    override suspend fun setChargingProfile(
        ocppSessionInfo: OcppSession.Info,
        request: SetChargingProfileRequest,
    ): SetChargingProfileConfirmation {
        val chargePoint = chargePointService.getByIdentity(ocppSessionInfo.identity)
        val connector = getConnector(ocppSessionInfo, request.connectorId)
            ?: return SetChargingProfileConfirmation(status = SetChargingProfileStatus.Rejected)
        val profile = request.csChargingProfiles
        var error: String? = null
        val accepted = transaction {
            error = validationError(chargePoint, connector, profile)
            if (error != null) return@transaction false
            if (profile.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile) {
                val activeTransaction = connector.activeTransaction
                if (activeTransaction == null ||
                    activeTransaction.endTime != null ||
                    !activeTransaction.isOwner(connector) ||
                    profile.transactionId == null ||
                    activeTransaction.externalId != profile.transactionId
                ) {
                    false
                } else {
                    // Preserve all valid legacy stacks before migrating the old
                    // single-profile transaction column to the profile store.
                    val legacy = activeTransaction.chargingProfile
                    if (legacy?.chargingProfileId != null &&
                        legacy.stackLevel != null &&
                        legacy.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile &&
                        ChargingProfileCalculator.validationError(legacy) == null
                    ) {
                        txDefaultService.store(
                            chargePoint,
                            connector,
                            legacy.copy(transactionId = activeTransaction.externalId),
                        )
                    }
                    activeTransaction.chargingProfile = null
                    txDefaultService.store(
                        chargePoint,
                        connector,
                        profile.copy(transactionId = activeTransaction.externalId),
                    )
                    true
                }
            } else {
                txDefaultService.store(chargePoint, connector, profile)
                true
            }
        }
        error?.let { GlobalLogger.warn(connector, it) }
        if (accepted) {
            txDefaultService.refreshChargingLimits(chargePoint)
            GlobalLogger.info(connector, "Stored ${profile.chargingProfilePurpose} profile ${profile.chargingProfileId}")
        }
        return SetChargingProfileConfirmation(
            status = if (accepted) SetChargingProfileStatus.Accepted else SetChargingProfileStatus.Rejected,
        )
    }

    private fun validationError(
        chargePoint: ChargePointDAO,
        connector: ChargePointConnectorDAO,
        profile: ChargingProfile,
    ): String? {
        val legacyProfiles = chargePoint.getActiveTransactions().mapNotNull { active ->
            active.chargingProfile?.let { StoredChargingProfile(active.connectorPosition, it) }
        }
        return ChargingProfileValidation.error(
            profile,
            chargePoint.configuration,
            connector.position,
            txDefaultService.listProfiles(chargePoint) + legacyProfiles,
        )
    }

    private fun getConnector(
        ocppSessionInfo: OcppSession.Info,
        connectorId: Int?,
    ): ChargePointConnectorDAO? {
        if (connectorId == null || connectorId < 0) return null
        val chargePoint = chargePointService.getByIdentity(ocppSessionInfo.identity)
        return if (connectorId == 0) {
            chargePoint.getConnector(0)
        } else {
            transaction { chargePoint.connectors.firstOrNull { it.position == connectorId } }
        }
    }
}

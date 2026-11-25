package com.monta.ocpp.emulator.v16.data.repository

import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileRequest
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepointconnector.entity.ChargePointConnectorDAO
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransaction
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import com.monta.ocpp.emulator.common.util.ChargingProfileCalculator
import com.monta.ocpp.emulator.v16.data.entity.TxDefault
import com.monta.ocpp.emulator.v16.data.entity.TxDefaultDAO
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import javax.inject.Singleton

data class StoredChargingProfile(
    val connectorId: Int,
    val profile: ChargingProfile,
)

@Singleton
class TxDefaultRepository {

    fun store(
        chargePointDAO: ChargePointDAO,
        connectorDAO: ChargePointConnectorDAO,
        chargingProfile: ChargingProfile,
    ): TxDefaultDAO {
        requireNotNull(chargingProfile.chargingProfileId) { "chargingProfileId is required" }
        requireNotNull(chargingProfile.chargingProfilePurpose) { "chargingProfilePurpose is required" }
        requireNotNull(chargingProfile.stackLevel) { "stackLevel is required" }

        // A profile ID is unique across the Charge Point. A new ID also replaces
        // the existing profile in the same connector/purpose/stack slot.
        TxDefaultDAO.find { TxDefault.chargePointId eq chargePointDAO.id }
            .filter { stored ->
                stored.chargingProfileId == chargingProfile.chargingProfileId ||
                    (
                        stored.connector.id == connectorDAO.id &&
                            stored.chargingProfilePurpose == chargingProfile.chargingProfilePurpose &&
                            stored.stackLevel == chargingProfile.stackLevel
                        )
            }
            .forEach { it.delete() }

        // Older emulator versions kept one TxProfile on the transaction row.
        // Remove a replaced legacy copy so it cannot reappear as a fallback.
        ChargePointTransactionDAO.find { ChargePointTransaction.chargePointId eq chargePointDAO.id }
            .forEach { transaction ->
                val legacy = transaction.chargingProfile ?: return@forEach
                if (legacy.chargingProfileId == chargingProfile.chargingProfileId ||
                    (
                        transaction.chargePointConnector.id == connectorDAO.id &&
                            legacy.chargingProfilePurpose == chargingProfile.chargingProfilePurpose &&
                            legacy.stackLevel == chargingProfile.stackLevel
                        )
                ) {
                    transaction.chargingProfile = null
                }
            }

        return TxDefaultDAO.newInstance(chargePointDAO, connectorDAO, chargingProfile)
    }

    fun list(
        chargePointDAO: ChargePointDAO,
    ): List<StoredChargingProfile> {
        return TxDefaultDAO.find { TxDefault.chargePointId eq chargePointDAO.id }
            .map { StoredChargingProfile(it.connector.position, it.txDefaultProfile) }
    }

    fun delete(
        chargePointDAO: ChargePointDAO,
        connectorDAO: ChargePointConnectorDAO?,
        request: ClearChargingProfileRequest,
        stackLevel: Int? = null,
    ): Int {
        val storedMatches = TxDefaultDAO.find { TxDefault.chargePointId eq chargePointDAO.id }
            .filter { stored ->
                (connectorDAO == null || stored.connector.id == connectorDAO.id) &&
                    matches(stored.txDefaultProfile, request, stackLevel)
            }
        storedMatches.forEach { it.delete() }
        return storedMatches.size
    }

    fun deleteTransaction(
        chargePointDAO: ChargePointDAO,
        transactionId: Int,
    ): Int {
        val matches = TxDefaultDAO.find { TxDefault.chargePointId eq chargePointDAO.id }
            .filter {
                it.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile &&
                    it.txDefaultProfile.transactionId == transactionId
            }
        matches.forEach { it.delete() }
        return matches.size
    }

    fun deleteUnboundTransactions(
        chargePointDAO: ChargePointDAO,
    ): Int {
        val matches = TxDefaultDAO.find { TxDefault.chargePointId eq chargePointDAO.id }
            .filter {
                it.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile && it.txDefaultProfile.transactionId == null
            }
        matches.forEach { it.delete() }
        return matches.size
    }

    fun findActive(
        chargePointDAO: ChargePointDAO,
        connectorDAO: ChargePointConnectorDAO,
        fallbackScheduleStart: Instant,
        chargingProfilePurpose: ChargingProfilePurposeType,
        transactionId: Int? = null,
        checkDate: Instant = Instant.now(),
    ): ChargingProfile? {
        return list(chargePointDAO)
            .filter {
                (it.connectorId == connectorDAO.position || it.connectorId == 0) &&
                    it.profile.chargingProfilePurpose == chargingProfilePurpose &&
                    (
                        chargingProfilePurpose != ChargingProfilePurposeType.TxProfile ||
                            (it.connectorId == connectorDAO.position && it.profile.transactionId == transactionId)
                        ) &&
                    ChargingProfileCalculator.isActive(it.profile, fallbackScheduleStart, checkDate)
            }
            .maxWithOrNull(
                compareBy<StoredChargingProfile> { it.profile.stackLevel ?: 0 }
                    .thenBy { it.connectorId == connectorDAO.position },
            )
            ?.profile
    }

    companion object {
        fun matches(
            profile: ChargingProfile,
            request: ClearChargingProfileRequest,
            stackLevel: Int? = null,
        ): Boolean {
            return (request.id == null || profile.chargingProfileId == request.id) &&
                (request.chargingProfilePurpose == null || profile.chargingProfilePurpose == request.chargingProfilePurpose) &&
                (stackLevel == null || profile.stackLevel == stackLevel)
        }
    }
}

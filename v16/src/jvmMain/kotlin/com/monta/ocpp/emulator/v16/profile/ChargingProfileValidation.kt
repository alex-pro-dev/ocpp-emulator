package com.monta.ocpp.emulator.v16.profile

import com.monta.library.ocpp.common.chargingprofile.ChargingRateUnit
import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.ocpp.emulator.common.util.ChargingProfileCalculator
import com.monta.ocpp.emulator.v16.data.repository.StoredChargingProfile

object ChargingProfileValidation {

    fun error(
        profile: ChargingProfile,
        configuration: Map<String, String?>,
        connectorId: Int,
        existingProfiles: List<StoredChargingProfile>,
    ): String? {
        if (configuration["SupportedFeatureProfiles"]?.split(",")?.none { it.trim() == "SmartCharging" } != false) {
            return "SmartCharging is not advertised by this charge point"
        }
        if (profile.chargingProfileId == null ||
            profile.stackLevel == null ||
            profile.stackLevel!! < 0 ||
            profile.chargingProfilePurpose == null
        ) {
            return "Charging profile ID, nonnegative stack level and purpose are required"
        }
        if (profile.chargingProfilePurpose == ChargingProfilePurposeType.ChargePointMaxProfile && connectorId != 0) {
            return "ChargePointMaxProfile must target connector 0"
        }
        if (profile.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile && connectorId <= 0) {
            return "TxProfile must target a physical connector"
        }
        if (profile.chargingProfilePurpose != ChargingProfilePurposeType.TxProfile && profile.transactionId != null) {
            return "Only TxProfile may specify a transaction"
        }
        ChargingProfileCalculator.validationError(profile)?.let { return it }
        val schedule = profile.chargingSchedule ?: return "Charging schedule is required"
        val maxStack = configuration["ChargeProfileMaxStackLevel"]?.toIntOrNull()
        if (maxStack != null && profile.stackLevel!! > maxStack) return "Stack level exceeds charger capability"
        val maxPeriods = configuration["ChargingScheduleMaxPeriods"]?.toIntOrNull()
        if (maxPeriods != null && schedule.chargingSchedulePeriod.size > maxPeriods) return "Too many charging periods"
        val rateUnits = configuration["ChargingScheduleAllowedChargingRateUnit"]
            ?.split(",")?.map { it.trim() }
        if (rateUnits != null &&
            (
                schedule.chargingRateUnit == ChargingRateUnit.A &&
                    "Current" !in rateUnits ||
                    schedule.chargingRateUnit == ChargingRateUnit.W &&
                    "Power" !in rateUnits
                )
        ) {
            return "Charging rate unit is not supported"
        }
        if (configuration["ConnectorSwitch3to1PhaseSupported"]?.equals("false", ignoreCase = true) == true &&
            schedule.chargingSchedulePeriod.map { it.numberPhases }.distinct().size > 1
        ) {
            return "Phase switching is not supported"
        }
        val maxProfiles = configuration["MaxChargingProfilesInstalled"]?.toIntOrNull()
        if (maxProfiles != null) {
            val remainingProfiles = existingProfiles.distinctBy { it.profile.chargingProfileId }.count { stored ->
                stored.profile.chargingProfileId != profile.chargingProfileId &&
                    !(
                        stored.connectorId == connectorId &&
                            stored.profile.chargingProfilePurpose == profile.chargingProfilePurpose &&
                            stored.profile.stackLevel == profile.stackLevel
                        )
            }
            if (remainingProfiles + 1 > maxProfiles) return "Charging profile storage capacity is exhausted"
        }
        return null
    }
}

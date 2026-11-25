package com.monta.ocpp.emulator.v16.profile

import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileConfirmation
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileRequest
import com.monta.ocpp.emulator.common.util.MontaSerialization
import kotlin.coroutines.cancellation.CancellationException

/** The OCPP dependency omits stackLevel from its ClearChargingProfile model. */
object ClearChargingProfileWireHandler {
    private val allowedProperties = setOf("id", "connectorId", "chargingProfilePurpose", "stackLevel")

    suspend fun handle(
        message: String,
        clear: suspend (ClearChargingProfileRequest, Int?) -> ClearChargingProfileConfirmation,
    ): String? {
        val mapper = MontaSerialization.objectMapper
        val frame = runCatching { mapper.readTree(message) }.getOrNull() ?: return null
        if (!frame.isArray || frame.size() < 3 || frame[2].asText() != "ClearChargingProfile") {
            return null
        }
        if (frame[0].isIntegralNumber && frame[0].canConvertToInt() && frame[0].intValue() != 2) return null
        val requestId = frame[1].asText()
        fun error(
            code: String,
            description: String,
        ): String = mapper.writeValueAsString(
            listOf(4, requestId, code, description, emptyMap<String, String>()),
        )
        if (!frame[0].isIntegralNumber ||
            !frame[0].canConvertToInt() ||
            frame[0].intValue() != 2 ||
            frame.size() != 4 ||
            !frame[1].isTextual ||
            !frame[3].isObject
        ) {
            return error("FormationViolation", "Invalid ClearChargingProfile request")
        }
        val payload = frame[3]
        val unknownProperty = payload.fieldNames().asSequence().firstOrNull { it !in allowedProperties }
        if (unknownProperty != null) {
            return error("PropertyConstraintViolation", "Unknown ClearChargingProfile property: $unknownProperty")
        }
        for (field in listOf("id", "connectorId", "stackLevel")) {
            val value = payload.get(field) ?: continue
            if (!value.isIntegralNumber || !value.canConvertToInt()) {
                return error("TypeConstraintViolation", "$field must be an integer")
            }
            if (field != "id" && value.intValue() < 0) {
                return error("PropertyConstraintViolation", "$field must be nonnegative")
            }
        }
        val purposeValue = payload.get("chargingProfilePurpose")
        if (purposeValue != null && !purposeValue.isTextual) {
            return error("TypeConstraintViolation", "chargingProfilePurpose must be a string")
        }
        val purpose = purposeValue?.asText()?.let { name ->
            ChargingProfilePurposeType.entries.firstOrNull { it.name == name }
                ?: return error("PropertyConstraintViolation", "Unknown charging profile purpose")
        }
        val request = ClearChargingProfileRequest(
            id = payload.get("id")?.intValue(),
            connectorId = payload.get("connectorId")?.intValue(),
            chargingProfilePurpose = purpose,
        )
        return try {
            val response = clear(request, payload.get("stackLevel")?.intValue())
            mapper.writeValueAsString(listOf(3, requestId, response))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error("InternalError", "Charging profiles could not be cleared")
        }
    }
}

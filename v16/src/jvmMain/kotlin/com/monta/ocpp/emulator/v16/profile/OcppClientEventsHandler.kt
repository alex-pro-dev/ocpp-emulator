package com.monta.ocpp.emulator.v16.profile

import com.monta.library.ocpp.common.session.OcppSession
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.common.util.injectAnywhere
import com.monta.ocpp.emulator.logger.GlobalLogger
import javax.inject.Singleton

@Singleton
class OcppClientEventsHandler {

    private val chargePointService: ChargePointService by injectAnywhere()

    suspend fun onConnect(
        ocppSessionInfo: OcppSession.Info,
        isReconnecting: Boolean,
    ) {
        val chargePoint = chargePointService.getByIdentity(ocppSessionInfo.identity)

        GlobalLogger.info(chargePoint, "Connected")
    }

    suspend fun onDisconnect(
        ocppSessionInfo: OcppSession.Info,
    ): Boolean {
        val chargePoint = chargePointService.getByIdentity(ocppSessionInfo.identity)
        GlobalLogger.info(chargePoint, "Disconnected")
        return true
    }
}

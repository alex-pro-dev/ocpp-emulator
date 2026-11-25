package com.monta.ocpp.emulator.interceptor

import com.monta.ocpp.emulator.chargepoint.repository.ChargePointRepository
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.interceptor.Interception
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MessageInterceptorConfigTest {
    @Test
    fun `concurrent UI and connection access shares complete settings without resetting choices`() = runBlocking {
        val interceptor = MessageInterceptor(ChargePointService(ChargePointRepository()))
        val start = CompletableDeferred<Unit>()
        val requests = List(32) {
            async(Dispatchers.Default) {
                start.await()
                interceptor.getConfig(59)
            }
        }
        start.complete(Unit)
        val configurations = requests.awaitAll()
        val configuration = configurations.first()
        configurations.forEach { assertSame(configuration, it) }
        assertEquals(
            MessageInterceptor.chargePointFeatures.keys + MessageInterceptor.centralSystemFeatures.keys,
            configuration.keys,
        )
        val boot = configuration.getValue("BootNotification")
        val blocked = Interception.Block(59)
        boot.onRequest.value = blocked
        repeat(10) {
            assertSame(boot, interceptor.getConfig(59).getValue("BootNotification"))
            assertSame(blocked, interceptor.getConfig(59).getValue("BootNotification").onRequest.value)
        }
        val otherCharger = interceptor.getConfig(60).getValue("BootNotification")
        assertNotSame(boot, otherCharger)
        assertSame(Interception.NoOp, otherCharger.onRequest.value)
    }
}

package com.monta.ocpp.emulator.chargepoint.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OcppServerTest {
    @Test
    fun `presets resolve to the configured EVCharge routes`() {
        assertEquals(
            "wss://ocpp.evcharge.mobi/ocpp/16j/Charger-A",
            stationWebSocketUrl(OcppServerPreset.Production.url, "Charger-A"),
        )
        assertEquals(
            "wss://staging.evcharge.mobi:18443/Charger-A",
            stationWebSocketUrl(OcppServerPreset.Staging.url, "Charger-A"),
        )
        assertEquals(
            OcppServerPreset.Production,
            OcppServerPreset.fromUrl("wss://ocpp.evcharge.mobi/ocpp/16j"),
        )
    }

    @Test
    fun `custom routes keep query parameters and append one encoded case-sensitive identity`() {
        val expected = "wss://example.test/site%20A/Charger%25A?test=a%2Bb"
        assertEquals(expected, stationWebSocketUrl("wss://example.test/site%20A/?test=a%2Bb", "Charger%A"))
        assertEquals(expected, stationWebSocketUrl(expected, "Charger%A"))
        assertEquals(
            "ws://[::1]:8888/Charger-B",
            stationWebSocketUrl("ws://[::1]:8888", "Charger-B"),
        )
    }

    @Test
    fun `invalid or ambiguous server URLs are rejected`() {
        listOf(
            "https://example.test",
            "wss://",
            "wss://example.test:99999",
            "wss://station:password@example.test",
            "wss://example.test/#fragment",
        ).forEach { assertNull(parseOcppServerUrl(it), it) }
    }
}

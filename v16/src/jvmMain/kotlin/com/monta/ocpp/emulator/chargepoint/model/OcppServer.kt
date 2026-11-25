package com.monta.ocpp.emulator.chargepoint.model

import java.net.URI
import java.net.URISyntaxException

enum class OcppServerPreset(
    val label: String,
    val url: String,
) {
    Production("EVCharge Production", "wss://ocpp.evcharge.mobi/ocpp/16j/"),
    Staging("EVCharge Staging", "wss://staging.evcharge.mobi:18443"),
    Local("Local", "ws://localhost:8888/ocpp/1.6j/"),
    Custom("Custom", ""),
    ;

    companion object {
        fun fromUrl(
            url: String,
        ): OcppServerPreset = entries.firstOrNull { it.url.trimEnd('/') == url.trimEnd('/') } ?: Custom
    }
}

internal fun parseOcppServerUrl(
    url: String,
): URI? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    return uri.takeIf {
        (it.scheme.equals("ws", ignoreCase = true) || it.scheme.equals("wss", ignoreCase = true)) &&
            it.host != null &&
            it.userInfo == null &&
            it.fragment == null &&
            (it.port == -1 || it.port in 1..65535)
    }
}

internal fun stationWebSocketUrl(
    serverUrl: String,
    identity: String,
): String {
    val server = requireNotNull(parseOcppServerUrl(serverUrl)) { "Invalid OCPP WebSocket URL" }
    val path = server.rawPath.trimEnd('/')
    val lastSegment = path.substringAfterLast('/')
    val existingIdentity = URI("/$lastSegment").path.drop(1)
    val stationPath = if (existingIdentity == identity) path else path + URI(null, null, "/$identity", null).toASCIIString()
    val query = server.rawQuery?.let { "?$it" }.orEmpty()
    return "${server.scheme}://${server.rawAuthority}$stationPath$query"
}

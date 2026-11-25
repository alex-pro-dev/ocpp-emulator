package com.monta.ocpp.emulator.chargepoint.view.screens

internal data class ChargePointTabs<T>(
    val points: List<T>,
    val selectedIndex: Int,
)

/** Keep the displayed point selected as connections change or arrive asynchronously. */
internal fun <T> chargePointTabs(
    current: T,
    connected: List<T>,
    id: (T) -> Long,
): ChargePointTabs<T> {
    val currentId = id(current)
    val selectedIndex = connected.indexOfFirst { id(it) == currentId }
    return if (selectedIndex >= 0) {
        ChargePointTabs(connected, selectedIndex)
    } else {
        ChargePointTabs(connected + current, connected.size)
    }
}

package com.monta.ocpp.emulator.chargepoint.view.screens

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChargePointTabsTest {

    private data class Point(val id: Long, val name: String = "")

    private val first = Point(1)
    private val second = Point(2)

    @Test
    fun `disconnecting the first charger keeps the second selected at its new index`() {
        val before = chargePointTabs(second, listOf(first, second)) { it.id }
        val after = chargePointTabs(second, listOf(second)) { it.id }

        assertEquals(1, before.selectedIndex)
        assertEquals(0, after.selectedIndex)
        assertEquals(second.id, after.points[after.selectedIndex].id)
    }

    @Test
    fun `reordering or inserting tabs preserves selection by charger ID`() {
        val third = Point(3)
        for (points in listOf(listOf(second, first), listOf(third, second, first))) {
            val tabs = chargePointTabs(second, points) { it.id }
            assertEquals(second.id, tabs.points[tabs.selectedIndex].id)
        }
    }

    @Test
    fun `empty connection list renders a selected fallback tab`() {
        val tabs = chargePointTabs(second, emptyList()) { it.id }

        assertEquals(listOf(second), tabs.points)
        assertEquals(0, tabs.selectedIndex)
    }

    @Test
    fun `missing current point stays visible while connection updates catch up`() {
        val tabs = chargePointTabs(second, listOf(first)) { it.id }

        assertEquals(listOf(first, second), tabs.points)
        assertEquals(second.id, tabs.points[tabs.selectedIndex].id)
    }

    @Test
    fun `navigation selects the new charger without retaining the old index`() {
        val points = listOf(first, second)
        val before = chargePointTabs(first, points) { it.id }
        val after = chargePointTabs(second, points) { it.id }

        assertEquals(first.id, before.points[before.selectedIndex].id)
        assertEquals(second.id, after.points[after.selectedIndex].id)
    }

    @Test
    fun `refreshed DAO instance with the same ID does not add a duplicate tab`() {
        val refreshed = second.copy(name = "Updated")
        val tabs = chargePointTabs(refreshed, listOf(first, second)) { it.id }

        assertEquals(2, tabs.points.size)
        assertEquals(second.id, tabs.points[tabs.selectedIndex].id)
    }
}

package com.monta.ocpp.emulator

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.monta.ocpp.emulator.chargepoint.view.createchargepoint.CreateChargePointPage
import com.monta.ocpp.emulator.chargepoint.view.screens.ChargePointPage
import com.monta.ocpp.emulator.chargepoint.view.screens.ChargePointsScreen
import com.monta.ocpp.emulator.common.BaseMontaWindow
import com.monta.ocpp.emulator.common.util.injectAnywhere
import com.monta.ocpp.emulator.common.view.NavigationViewModel
import com.monta.ocpp.emulator.theme.AppThemeViewModel
import com.monta.ocpp.emulator.theme.setupAppThemeMenu
import com.monta.ocpp.emulator.vehicle.view.VehicleScreen

@Preview
@Composable
fun ApplicationScope.MainWindow() {
    val appThemeViewModel: AppThemeViewModel by injectAnywhere()
    val navigationViewModel: NavigationViewModel by injectAnywhere()

    val windowState = rememberWindowState(
        size = DpSize(1140.dp, 820.dp),
        position = WindowPosition.Aligned(
            Alignment.Center,
        ),
    )

    BaseMontaWindow(
        title = "EVCharge OCPP Emulator — 1.6J",
        state = windowState,
        windowGainedFocus = {
            navigationViewModel.windowHasFocus = true
        },
        windowLostFocus = {
            navigationViewModel.windowHasFocus = false
        },
    ) {
        setupAppThemeMenu(appThemeViewModel)

        MaterialTheme(
            colors = appThemeViewModel.getColors(),
        ) {
            when (navigationViewModel.currentScreen) {
                is NavigationViewModel.Screen.ChargePoints -> {
                    ChargePointsScreen()
                }

                is NavigationViewModel.Screen.CreateChargePoint -> {
                    CreateChargePointPage(navigationViewModel.getChargePointToEdit())
                }

                is NavigationViewModel.Screen.ChargePoint -> {
                    ChargePointPage(navigationViewModel.getChargePointId())
                }

                is NavigationViewModel.Screen.Vehicles -> {
                    VehicleScreen()
                }
            }
        }
    }
}

package com.monta.ocpp.emulator.chargepoint.view.createchargepoint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointTable
import com.monta.ocpp.emulator.chargepoint.model.MeterType
import com.monta.ocpp.emulator.chargepoint.model.OcppServerPreset
import com.monta.ocpp.emulator.chargepoint.model.parseOcppServerUrl
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.common.components.FormInput
import com.monta.ocpp.emulator.common.components.LabelledCheckBox
import com.monta.ocpp.emulator.common.components.PasswordField
import com.monta.ocpp.emulator.common.components.Spinner
import com.monta.ocpp.emulator.common.components.getCardStyle
import com.monta.ocpp.emulator.common.util.injectAnywhere
import com.monta.ocpp.emulator.common.view.NavigationViewModel
import com.monta.ocpp.emulator.v16.connection.ConnectionManager
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.annotation.Factory

private const val MAX_CONNECTORS = 32

@Composable
fun ChargePointForm(
    viewModel: ChargePointFormViewModel,
    chargePoint: ChargePointDAO?,
) {
    chargePoint?.let {
        if (!viewModel.initialized) {
            viewModel.form.updateFromDAO(it)
            viewModel.form = viewModel.form.copy()
            viewModel.initialized = true
            viewModel.isUpdating = true
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(
                state = rememberScrollState(),
            ),
    ) {
        Card(
            modifier = getCardStyle()
                .align(Alignment.Center)
                .padding(
                    top = 32.dp,
                    bottom = 32.dp,
                ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
                    .width(400.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = (if (viewModel.isUpdating) "Edit" else "Create") + " Charge Point",
                    style = MaterialTheme.typography.h5,
                    modifier = Modifier.padding(8.dp)
                        .align(Alignment.Start)
                        .fillMaxWidth(),
                )
                FormInput(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Charge Point Name",
                    value = viewModel.form.chargePointName,
                    onValueChange = { newValue ->
                        viewModel.form = viewModel.form.copy(
                            chargePointName = newValue,
                        )
                    },
                    helperText = "Name used for identifying the charge point locally in the app, not largely important mostly for your convenience",
                )
                FormInput(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Charge Point Identity",
                    value = viewModel.form.chargePointIdentity,
                    onValueChange = { newValue ->
                        viewModel.form = viewModel.form.copy(
                            chargePointIdentity = newValue,
                        )
                        viewModel.formErrors.remove("identity")
                    },
                    enabled = !viewModel.isUpdating,
                    isError = viewModel.formErrors.contains("identity"),
                    helperText = viewModel.formErrors.getOrDefault("identity", null)
                        ?: "Use the case-sensitive station identity registered on the OCPP server.",
                )
                Spinner(
                    modifier = Modifier.fillMaxWidth(),
                    label = "OCPP Server",
                    value = viewModel.form.serverPreset,
                    values = OcppServerPreset.entries,
                    render = { it.label },
                ) { preset ->
                    viewModel.form = viewModel.form.copy(
                        serverPreset = preset,
                        ocppUrl = if (preset == OcppServerPreset.Custom) viewModel.form.ocppUrl else preset.url,
                    )
                    viewModel.formErrors.remove("ocppUrl")
                }
                FormInput(
                    modifier = Modifier.fillMaxWidth(),
                    label = "OCPP WebSocket URL",
                    value = viewModel.form.ocppUrl,
                    onValueChange = { url ->
                        viewModel.form = viewModel.form.copy(ocppUrl = url)
                        viewModel.formErrors.remove("ocppUrl")
                    },
                    enabled = viewModel.form.serverPreset == OcppServerPreset.Custom,
                    isError = viewModel.formErrors.contains("ocppUrl"),
                    helperText = viewModel.formErrors["ocppUrl"]
                        ?: "The emulator appends the station identity if the URL does not already end with it.",
                )
                FormInput(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Number of connectors",
                    value = viewModel.form.connectorCount,
                    onValueChange = { count ->
                        viewModel.form = viewModel.form.copy(connectorCount = count)
                        viewModel.formErrors.remove("connectorCount")
                    },
                    enabled = !viewModel.isUpdating,
                    isError = viewModel.formErrors.contains("connectorCount"),
                    helperText = viewModel.formErrors["connectorCount"] ?: "Choose 1 to $MAX_CONNECTORS connectors.",
                )
                FormInput(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Maximum station power (kW)",
                    value = viewModel.form.maxKw,
                    onValueChange = { power ->
                        viewModel.form = viewModel.form.copy(maxKw = power)
                        viewModel.formErrors.remove("maxKw")
                    },
                    isError = viewModel.formErrors.contains("maxKw"),
                    helperText = viewModel.formErrors["maxKw"],
                )
                PasswordField(
                    modifier = Modifier.fillMaxWidth(),
                    password = viewModel.form.password,
                    passwordVisibility = viewModel.form.passwordVisibility,
                    passwordListener = { password ->
                        viewModel.form = viewModel.form.copy(password = password)
                    },
                    passwordVisibilityListener = { isVisible ->
                        viewModel.form = viewModel.form.copy(passwordVisibility = isVisible)
                    },
                )

                LabelledCheckBox(
                    modifier = Modifier.fillMaxWidth(),
                    checked = viewModel.form.showAdvanceSettings,
                    onCheckedChange = {
                        viewModel.form = viewModel.form.copy(
                            showAdvanceSettings = it,
                        )
                    },
                    label = "Show Advanced Settings",
                )

                if (viewModel.form.showAdvanceSettings) {
                    FormInput(
                        modifier = Modifier.fillMaxWidth(),
                        label = "Payment API URL (optional)",
                        value = viewModel.form.apiUrl,
                        onValueChange = { newValue ->
                            viewModel.form = viewModel.form.copy(
                                apiUrl = newValue,
                            )
                        },
                    )
                    FormInput(
                        modifier = Modifier.fillMaxWidth(),
                        label = "Firmware Version",
                        value = viewModel.form.firmware,
                        onValueChange = { newValue ->
                            viewModel.form = viewModel.form.copy(
                                firmware = newValue,
                            )
                        },
                    )
                    Spinner(
                        modifier = Modifier.fillMaxWidth(),
                        label = "Meter Type",
                        value = viewModel.form.meterType,
                        values = MeterType.entries,
                        render = { it.displayName },
                    ) { newMeterType ->
                        viewModel.form = viewModel.form.copy(
                            meterType = newMeterType,
                        )
                    }
                }
                Button(
                    modifier = Modifier.align(Alignment.Start),
                    onClick = {
                        connect(viewModel)
                    },
                ) {
                    if (viewModel.isUpdating) {
                        Text("Save")
                    } else {
                        Text("Create")
                    }
                }
            }
        }
    }
}

private fun connect(
    viewModel: ChargePointFormViewModel,
) {
    val navigationViewModel: NavigationViewModel by injectAnywhere()
    val chargePointService: ChargePointService by injectAnywhere()

    if (viewModel.validateForm()) {
        return
    }

    val savedChargePoint = chargePointService.upsert(
        name = viewModel.form.chargePointName,
        identity = viewModel.form.chargePointIdentity.trim(),
        password = viewModel.form.password.takeIf { it.isNotBlank() },
        ocppUrl = viewModel.form.ocppUrl.trim(),
        apiUrl = viewModel.form.apiUrl,
        firmware = viewModel.form.firmware,
        maxKw = viewModel.form.maxKw.toDouble(),
        connectorCount = viewModel.form.connectorCount.toInt(),
        meterType = viewModel.form.meterType,
    )

    val connectionManager: ConnectionManager by injectAnywhere()
    connectionManager.reconnect(savedChargePoint.id.value, 0)

    navigationViewModel.navigateTo(
        screen = NavigationViewModel.Screen.ChargePoints,
    )
}

@Factory
class ChargePointFormViewModel {

    var form by mutableStateOf(Form())
    var initialized by mutableStateOf(false)
    var isUpdating by mutableStateOf(false)

    val formErrors = mutableStateMapOf<String, String>()

    fun validateForm(): Boolean {
        formErrors.clear()

        if (parseOcppServerUrl(form.ocppUrl) == null) {
            formErrors["ocppUrl"] = "Enter a valid ws:// or wss:// URL."
        }

        val identity = form.chargePointIdentity.trim()
        if (identity.isEmpty() ||
            identity.length > 512 ||
            identity in setOf(".", "..") ||
            identity.any { it.isWhitespace() || it.isISOControl() || it in "/\\:?#" }
        ) {
            formErrors["identity"] = "Enter a station identity with no spaces or URL separators."
        } else if (!isUpdating && transaction { ChargePointDAO.count(ChargePointTable.identity eq identity) > 0 }) {
            formErrors["identity"] = "Identity already in use."
        }

        val connectorCount = form.connectorCount.toIntOrNull()
        if (connectorCount == null || (!isUpdating && connectorCount !in 1..MAX_CONNECTORS)) {
            formErrors["connectorCount"] = "Enter a whole number from 1 to $MAX_CONNECTORS."
        }

        val maxKw = form.maxKw.toDoubleOrNull()
        if (maxKw == null || maxKw !in 0.1..1000.0) {
            formErrors["maxKw"] = "Enter a power from 0.1 to 1000 kW."
        }

        return formErrors.isNotEmpty()
    }

    data class Form(
        var chargePointName: String = "",
        var chargePointIdentity: String = "",
        var password: String = "",
        var passwordVisibility: Boolean = false,
        var serverPreset: OcppServerPreset = OcppServerPreset.Staging,
        var ocppUrl: String = OcppServerPreset.Staging.url,
        var apiUrl: String = "",
        var connectorCount: String = "1",
        var showAdvanceSettings: Boolean = false,
        var firmware: String = "1.0.0",
        var maxKw: String = "22",
        var meterType: MeterType = MeterType.OCPP,
    ) {
        fun updateFromDAO(
            chargePoint: ChargePointDAO,
        ) {
            this.chargePointName = chargePoint.name
            this.chargePointIdentity = chargePoint.identity
            this.password = chargePoint.basicAuthPassword ?: this.password
            this.serverPreset = OcppServerPreset.fromUrl(chargePoint.ocppUrl)
            this.ocppUrl = chargePoint.ocppUrl
            this.apiUrl = chargePoint.apiUrl
            this.connectorCount = transaction {
                chargePoint.getConnectors().count().coerceAtLeast(1).toString()
            }
            this.firmware = chargePoint.firmware
            this.maxKw = chargePoint.maxKw.toString()
            this.meterType = chargePoint.meterType
        }
    }
}

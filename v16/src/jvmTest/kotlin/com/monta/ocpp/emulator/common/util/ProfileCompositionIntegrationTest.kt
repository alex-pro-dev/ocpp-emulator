package com.monta.ocpp.emulator.common.util

import com.monta.library.ocpp.common.chargingprofile.ChargingProfileKind
import com.monta.library.ocpp.common.chargingprofile.ChargingRateUnit
import com.monta.library.ocpp.common.session.OcppSession
import com.monta.library.ocpp.common.session.OcppSessionRepository
import com.monta.library.ocpp.common.transport.OcppSettings
import com.monta.library.ocpp.v16.client.OcppClientV16Builder
import com.monta.library.ocpp.v16.core.ChangeConfigurationRequest
import com.monta.library.ocpp.v16.core.ChargePointStatus
import com.monta.library.ocpp.v16.core.ConfigurationStatus
import com.monta.library.ocpp.v16.core.GetConfigurationRequest
import com.monta.library.ocpp.v16.core.RemoteStartStopStatus
import com.monta.library.ocpp.v16.core.RemoteStartTransactionRequest
import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.library.ocpp.v16.smartcharge.ChargingSchedule
import com.monta.library.ocpp.v16.smartcharge.ChargingSchedulePeriod
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileRequest
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileStatus
import com.monta.library.ocpp.v16.smartcharge.GetCompositeScheduleRequest
import com.monta.library.ocpp.v16.smartcharge.GetCompositeScheduleStatus
import com.monta.library.ocpp.v16.smartcharge.SetChargingProfileRequest
import com.monta.library.ocpp.v16.smartcharge.SetChargingProfileStatus
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointTable
import com.monta.ocpp.emulator.chargepoint.repository.ChargePointRepository
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.chargepointconnector.entity.ChargePointConnectorDAO
import com.monta.ocpp.emulator.chargepointconnector.entity.ChargePointConnectorTable
import com.monta.ocpp.emulator.chargepointconnector.model.CarState
import com.monta.ocpp.emulator.chargepointconnector.repository.ChargePointConnectorRepository
import com.monta.ocpp.emulator.chargepointconnector.service.ChargePointConnectorService
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransaction
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import com.monta.ocpp.emulator.chargepointtransaction.repository.ChargePointTransactionRepository
import com.monta.ocpp.emulator.chargepointtransaction.service.ChargePointTransactionService
import com.monta.ocpp.emulator.common.upgradeSavedSmartChargingCapabilities
import com.monta.ocpp.emulator.v16.data.entity.TxDefault
import com.monta.ocpp.emulator.v16.data.repository.TxDefaultRepository
import com.monta.ocpp.emulator.v16.data.service.TxDefaultService
import com.monta.ocpp.emulator.v16.profile.ChargingProfileValidation
import com.monta.ocpp.emulator.v16.profile.ClearChargingProfileWireHandler
import com.monta.ocpp.emulator.v16.profile.CoreClientHandler
import com.monta.ocpp.emulator.v16.profile.SmartChargingClientHandler
import com.monta.ocpp.emulator.v16.profile.configuration.ChangeConfigurationService
import com.monta.ocpp.emulator.v16.start
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCompositionIntegrationTest {

    private val start = Instant.parse("2026-11-10T10:00:00Z")

    private data class Fixture(
        val point: ChargePointDAO,
        val ports: List<ChargePointConnectorDAO>,
        val sessions: List<ChargePointTransactionDAO>,
        val repository: TxDefaultRepository,
        val service: TxDefaultService,
    ) {
        val handler = SmartChargingClientHandler(
            ChargePointService(ChargePointRepository()),
            ChargePointTransactionService(ChargePointTransactionRepository()),
            service,
        )
    }

    // Every test owns one in-memory connection. Nested service transactions reuse
    // this transaction; no configured emulator database or socket is touched.
    private fun fixture(
        connectors: Int = 1,
        stationKw: Double = 44.0,
        createTransactions: Boolean = true,
        block: Fixture.() -> Unit,
    ) {
        val database = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(database) {
            SchemaUtils.create(ChargePointTable, ChargePointConnectorTable, ChargePointTransaction, TxDefault)
            val point = ChargePointDAO.newInstance(
                name = "Profile test station",
                identity = "PROFILE-TEST",
                password = null,
                ocppUrl = "ws://127.0.0.1/unused",
                apiUrl = "http://127.0.0.1/unused",
                firmware = "test",
                maxKw = stationKw,
            )
            point.updateConfiguration {
                this["SupportedFeatureProfiles"] = "Core,SmartCharging"
                this["ConnectorSwitch3to1PhaseSupported"] = "true"
            }
            point.getConnector(0)
            val ports = (1..connectors).map { position ->
                point.getConnector(position).apply {
                    maxKw = 22.0
                    vehicleNumberPhases = 3
                    vehicleMaxAmpsPerPhase = 80.0
                }
            }
            val sessions = if (createTransactions) {
                ports.map { port ->
                    ChargePointTransactionDAO.newInstance(
                        chargePoint = point,
                        chargePointConnector = port,
                        externalId = 100 + port.position,
                        idTag = "test",
                        startTime = start,
                    ).also { port.activeTransaction = it }
                }
            } else {
                emptyList()
            }
            val repository = TxDefaultRepository()
            Fixture(point, ports, sessions, repository, TxDefaultService(repository)).block()
        }
    }

    private fun profile(
        id: Int,
        limit: Double,
        purpose: ChargingProfilePurposeType = ChargingProfilePurposeType.TxDefaultProfile,
        stack: Int = 0,
        unit: ChargingRateUnit = ChargingRateUnit.A,
        phases: Int = 3,
        transactionId: Int? = null,
        minimum: Double? = null,
        periods: List<Pair<Int, Double>> = listOf(0 to limit),
    ) = ChargingProfile(
        chargingProfileId = id,
        transactionId = transactionId,
        stackLevel = stack,
        chargingProfilePurpose = purpose,
        chargingProfileKind = ChargingProfileKind.Absolute,
        chargingSchedule = ChargingSchedule(
            startSchedule = start.atZone(ZoneOffset.UTC),
            chargingRateUnit = unit,
            chargingSchedulePeriod = periods.map { (offset, value) ->
                ChargingSchedulePeriod(startPeriod = offset, limit = value, numberPhases = phases)
            },
            minChargingRate = minimum,
        ),
    )

    private fun Fixture.withCoreHandler(
        block: (CoreClientHandler) -> Unit,
    ) {
        val profiles = service
        startKoin {
            modules(
                module {
                    single { ChargePointService(ChargePointRepository()) }
                    single { ChargePointConnectorService(ChargePointConnectorRepository()) }
                    single { ChangeConfigurationService() }
                    single { profiles }
                },
            )
        }
        try {
            block(CoreClientHandler())
        } finally {
            stopKoin()
        }
    }

    private fun Fixture.confirmStart(): ChargePointTransactionDAO = ChargePointTransactionDAO.newInstance(
        chargePoint = point,
        chargePointConnector = ports.first(),
        externalId = 701,
        idTag = "pending-test",
        startTime = start,
        endMeterAt = start,
    ).also { ports.first().activeTransaction = it }

    private fun assertInitialStartStatus(
        initialState: CarState,
        limit: Double,
        expectedStatus: ChargePointStatus,
    ) = fixture(createTransactions = false) {
        val port = ports.first()
        port.carState = initialState
        val pending = service.reserveRemoteStartProfile(point, port, profile(80, limit, ChargingProfilePurposeType.TxProfile))
        val statuses = mutableListOf<ChargePointStatus>()
        val client = OcppClientV16Builder()
            .settings(OcppSettings())
            .localMode(OcppSessionRepository())
            .onConnect { _, _ -> }
            .onDisconnect { false }
            .addCore(CoreClientHandler())
            .build()
        val profiles = service
        startKoin {
            modules(
                module {
                    single { client }
                    single { profiles }
                    single { ChargePointTransactionService(ChargePointTransactionRepository()) }
                },
            )
        }
        try {
            runBlocking {
                // Exercise the real start confirmation callback and capture its
                // wire statuses without a socket or a scheduler correction.
                client.connect(
                    identity = point.identity,
                    isReconnecting = false,
                    sendFrame = { message ->
                        val frame = MontaSerialization.objectMapper.readTree(message)
                        val response = when (frame[2].asText()) {
                            "StartTransaction" -> mapOf("transactionId" to 701, "idTagInfo" to mapOf("status" to "Accepted"))
                            "StatusNotification" -> {
                                statuses.add(ChargePointStatus.valueOf(frame[3]["status"].asText()))
                                emptyMap<String, String>()
                            }
                            else -> error("Unexpected OCPP request: $message")
                        }
                        client.receiveMessage(
                            point.identity,
                            MontaSerialization.objectMapper.writeValueAsString(listOf(3, frame[1].asText(), response)),
                        )
                    },
                    closeConnection = {},
                )
                port.start("pending-test", pending)
            }
            assertEquals(listOf(ChargePointStatus.Preparing, expectedStatus), statuses)
            assertEquals(if (initialState == CarState.A) CarState.B else initialState, port.carState)
            assertEquals(701, port.activeTransaction?.externalId)
            assertEquals(701, repository.list(point).single().profile.transactionId)
            assertEquals(limit > 0.0, port.kw > 0.0)
            assertEquals(initialState == CarState.C && limit > 0.0, service.calculateChargingLimits(point).getValue(1).watts > 0.0)
        } finally {
            runBlocking { client.onDisconnect(point.identity) }
            stopKoin()
        }
    }

    @Test
    fun `starting an unplugged vehicle reports EV suspension when power is offered`() = assertInitialStartStatus(CarState.A, 16.0, ChargePointStatus.SuspendedEV)

    @Test
    fun `starting an unplugged vehicle preserves EVSE suspension for a zero profile`() = assertInitialStartStatus(CarState.A, 0.0, ChargePointStatus.SuspendedEVSE)

    @Test
    fun `starting a ready vehicle still reports charging with its transaction profile`() = assertInitialStartStatus(CarState.C, 16.0, ChargePointStatus.Charging)

    @Test
    fun `clear fences a remote start while its transaction confirmation is delayed`() = fixture(createTransactions = false) {
        val pending = service.reserveRemoteStartProfile(point, ports.first(), profile(80, 8.0, ChargingProfilePurposeType.TxProfile))
        assertTrue(service.isRemoteStartPending(pending))
        runBlocking {
            val confirmation = CompletableDeferred<Unit>()
            val install = async {
                confirmation.await()
                service.installRemoteStartProfile(pending, point, ports.first(), confirmStart())
            }
            val cleared = handler.clearChargingProfile(OcppSession.Info("", point.identity), ClearChargingProfileRequest(id = 80))
            assertEquals(ClearChargingProfileStatus.Accepted, cleared.status)
            confirmation.complete(Unit)
            assertFalse(install.await())
        }
        assertFalse(service.isRemoteStartPending(pending))
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `nonmatching connector purpose and stack clears preserve an accepted pending profile`() = fixture(createTransactions = false) {
        val pending = service.reserveRemoteStartProfile(point, ports.first(), profile(80, 8.0, ChargingProfilePurposeType.TxProfile))
        val requests = listOf(
            ClearChargingProfileRequest(id = 81) to null,
            ClearChargingProfileRequest(id = 80, connectorId = 0) to null,
            ClearChargingProfileRequest(id = 80, chargingProfilePurpose = ChargingProfilePurposeType.ChargePointMaxProfile) to null,
            ClearChargingProfileRequest(id = 80, connectorId = 1) to 1,
        )
        for ((request, stack) in requests) {
            val response = runBlocking { handler.clearChargingProfile(OcppSession.Info("", point.identity), request, stack) }
            assertEquals(ClearChargingProfileStatus.Unknown, response.status)
            assertTrue(service.isRemoteStartPending(pending))
        }
        val session = confirmStart()
        assertTrue(service.installRemoteStartProfile(pending, point, ports.first(), session))
        assertEquals(session.externalId, repository.list(point).single().profile.transactionId)
    }

    @Test
    fun `clearing pending stack zero preserves defaults and rejects a late authorization lease`() = fixture(createTransactions = false) {
        service.store(point, ports.first(), profile(10, 16.0))
        val pending = service.reserveRemoteStartProfile(point, ports.first(), profile(80, 8.0, ChargingProfilePurposeType.TxProfile))
        val response = runBlocking {
            handler.clearChargingProfile(
                OcppSession.Info("", point.identity),
                ClearChargingProfileRequest(connectorId = 1, chargingProfilePurpose = ChargingProfilePurposeType.TxProfile),
                0,
            )
        }
        assertEquals(ClearChargingProfileStatus.Accepted, response.status)
        assertFalse(service.isRemoteStartPending(pending))
        service.finishRemoteStart(pending)
        assertEquals(listOf(10), repository.list(point).map { it.profile.chargingProfileId })
    }

    @Test
    fun `a cleared remote start cannot bind or delete a newer reservation with the same profile ID`() = fixture(createTransactions = false) {
        val original = profile(80, 8.0, ChargingProfilePurposeType.TxProfile)
        val old = service.reserveRemoteStartProfile(point, ports.first(), original)
        service.clear(point, ports.first(), ClearChargingProfileRequest(id = 80))
        val fresh = service.reserveRemoteStartProfile(point, ports.first(), original.copy(stackLevel = 1))
        val session = confirmStart()
        assertFalse(service.installRemoteStartProfile(old, point, ports.first(), session))
        service.finishRemoteStart(old)
        assertTrue(service.isRemoteStartPending(fresh))
        assertTrue(service.installRemoteStartProfile(fresh, point, ports.first(), session))
        service.finishRemoteStart(fresh)
        assertEquals(1, repository.list(point).single().profile.stackLevel)
    }

    @Test
    fun `connection reset and failed authorization discard only unbound profiles`() = fixture {
        service.store(point, ports.first(), profile(10, 16.0))
        service.store(point, ports.first(), profile(11, 8.0, ChargingProfilePurposeType.TxProfile, transactionId = sessions.first().externalId))
        val pending = service.reserveRemoteStartProfile(point, ports.first(), profile(80, 4.0, ChargingProfilePurposeType.TxProfile, stack = 1))
        service.cancelPendingRemoteStarts(point)
        assertFalse(service.isRemoteStartPending(pending))
        assertFalse(service.installRemoteStartProfile(pending, point, ports.first(), sessions.first()))
        assertEquals(setOf(10, 11), repository.list(point).map { it.profile.chargingProfileId }.toSet())
        val failed = service.reserveRemoteStartProfile(point, ports.first(), profile(81, 4.0, ChargingProfilePurposeType.TxProfile, stack = 1))
        service.finishRemoteStart(failed)
        assertFalse(service.isRemoteStartPending(failed))
        assertEquals(setOf(10, 11), repository.list(point).map { it.profile.chargingProfileId }.toSet())
    }

    @Test
    fun `ended transactions cannot acquire a delayed remote start profile`() = fixture {
        val pending = service.reserveRemoteStartProfile(point, ports.first(), profile(80, 4.0, ChargingProfilePurposeType.TxProfile))
        sessions.first().endTime = start.plusSeconds(1)
        assertFalse(service.installRemoteStartProfile(pending, point, ports.first(), sessions.first()))
        service.finishRemoteStart(pending)
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `pending remote starts count toward installed profile capacity until cleared`() = fixture(connectors = 2, createTransactions = false) {
        point.updateConfiguration { this["MaxChargingProfilesInstalled"] = "1" }
        service.reserveRemoteStartProfile(point, ports.first(), profile(80, 4.0, ChargingProfilePurposeType.TxProfile))
        val next = profile(81, 8.0, ChargingProfilePurposeType.TxProfile)
        assertNotNull(ChargingProfileValidation.error(next, point.configuration, 2, repository.list(point)))
        service.clear(point, null, ClearChargingProfileRequest(id = 80))
        assertNull(ChargingProfileValidation.error(next, point.configuration, 2, repository.list(point)))
    }

    @Test
    fun `GetConfiguration reports actual readonly metadata for all and selected keys`() = fixture(connectors = 2, createTransactions = false) {
        val readonlyKeys = setOf(
            "NumberOfConnectors",
            "GetConfigurationMaxKeys",
            "SendLocalListMaxLength",
            "SupportedFeatureProfiles",
            "ChargeProfileMaxStackLevel",
            "ChargingScheduleAllowedChargingRateUnit",
            "ChargingScheduleMaxPeriods",
            "MaxChargingProfilesInstalled",
            "ConnectorSwitch3to1PhaseSupported",
        )
        point.updateConfiguration { this["VendorSetting"] = "custom" }
        withCoreHandler { core ->
            val all = runBlocking { core.getConfigurationRequest(OcppSession.Info("", point.identity), GetConfigurationRequest()) }
            val values = all.configurationKey.orEmpty().associateBy { it.key }
            assertEquals("2", values.getValue("NumberOfConnectors").value)
            readonlyKeys.forEach { key -> assertEquals(true, values.getValue(key).readonly, key) }
            listOf("HeartbeatInterval", "MeterValueSampleInterval", "FreeCharging", "VendorSetting").forEach { key ->
                assertEquals(false, values.getValue(key).readonly, key)
            }
            val selected = runBlocking {
                core.getConfigurationRequest(
                    OcppSession.Info("", point.identity),
                    GetConfigurationRequest(key = listOf("SupportedFeatureProfiles", "HeartbeatInterval", "MissingKey")),
                )
            }
            assertEquals(setOf("SupportedFeatureProfiles", "HeartbeatInterval"), selected.configurationKey.orEmpty().map { it.key }.toSet())
            assertEquals(listOf("MissingKey"), selected.unknownKey)
            assertEquals(true, selected.configurationKey.orEmpty().first { it.key == "SupportedFeatureProfiles" }.readonly)
        }
    }

    @Test
    fun `ChangeConfiguration rejects readonly capabilities without modifying saved values`() = fixture(createTransactions = false) {
        val readonlyKeys = listOf(
            "NumberOfConnectors",
            "GetConfigurationMaxKeys",
            "SendLocalListMaxLength",
            "SupportedFeatureProfiles",
            "ChargeProfileMaxStackLevel",
            "ChargingScheduleAllowedChargingRateUnit",
            "ChargingScheduleMaxPeriods",
            "MaxChargingProfilesInstalled",
            "ConnectorSwitch3to1PhaseSupported",
        )
        val before = point.configuration.toMap()
        withCoreHandler { core ->
            readonlyKeys.forEach { key ->
                val response = runBlocking {
                    core.changeConfigurationRequest(OcppSession.Info("", point.identity), ChangeConfigurationRequest(key = key, value = "changed"))
                }
                assertEquals(ConfigurationStatus.Rejected, response.status, key)
            }
        }
        point.flush()
        val stored = ChargePointTable.selectAll().single()[ChargePointTable.configuration]
        assertEquals(before, stored.toMap())
    }

    @Test
    fun `ChangeConfiguration still persists writable and vendor keys and identifies unknown keys`() = fixture(createTransactions = false) {
        point.updateConfiguration { this["VendorSetting"] = "before" }
        withCoreHandler { core ->
            mapOf("HeartbeatInterval" to "60", "VendorSetting" to "after").forEach { (key, value) ->
                val response = runBlocking {
                    core.changeConfigurationRequest(OcppSession.Info("", point.identity), ChangeConfigurationRequest(key = key, value = value))
                }
                assertEquals(ConfigurationStatus.Accepted, response.status, key)
            }
            val unknown = runBlocking {
                core.changeConfigurationRequest(OcppSession.Info("", point.identity), ChangeConfigurationRequest(key = "MissingKey", value = "value"))
            }
            assertEquals(ConfigurationStatus.NotSupported, unknown.status)
        }
        point.flush()
        val stored = ChargePointTable.selectAll().single()[ChargePointTable.configuration]
        assertEquals("60", stored["HeartbeatInterval"])
        assertEquals("after", stored["VendorSetting"])
        assertFalse(stored.containsKey("MissingKey"))
    }

    @Test
    fun `startup upgrades legacy saved capabilities and persists other settings`() = fixture(createTransactions = false) {
        point.updateConfiguration {
            this["SupportedFeatureProfiles"] = "Core,FirmwareManagement,VendorFeature"
            this["HeartbeatInterval"] = "37"
            this["ChargingScheduleMaxPeriods"] = "64"
            this["VendorSetting"] = "keep"
        }

        assertEquals(1, upgradeSavedSmartChargingCapabilities())
        point.flush()
        val stored = ChargePointTable.selectAll().single()[ChargePointTable.configuration]
        assertEquals("Core,FirmwareManagement,VendorFeature,SmartCharging", stored["SupportedFeatureProfiles"])
        assertEquals("37", stored["HeartbeatInterval"])
        assertEquals("64", stored["ChargingScheduleMaxPeriods"])
        assertEquals("keep", stored["VendorSetting"])
        assertEquals(0, upgradeSavedSmartChargingCapabilities())
        val response = runBlocking {
            handler.setChargingProfile(OcppSession.Info("", point.identity), SetChargingProfileRequest(connectorId = 1, csChargingProfiles = profile(1, 8.0)))
        }
        assertEquals(SetChargingProfileStatus.Accepted, response.status)
    }

    @Test
    fun `startup preserves already advertised SmartCharging without duplicates`() = fixture(createTransactions = false) {
        point.updateConfiguration { this["SupportedFeatureProfiles"] = "Core, SmartCharging, FirmwareManagement" }
        assertEquals(0, upgradeSavedSmartChargingCapabilities())
        assertEquals("Core, SmartCharging, FirmwareManagement", point.configuration["SupportedFeatureProfiles"])
    }

    @Test
    fun `startup fixes noncanonical SmartCharging names before accepting profiles`() = fixture(createTransactions = false) {
        point.updateConfiguration { this["SupportedFeatureProfiles"] = "Core,sMaRtChArGiNg,VendorFeature" }
        assertEquals(1, upgradeSavedSmartChargingCapabilities())
        assertEquals("Core,VendorFeature,SmartCharging", point.configuration["SupportedFeatureProfiles"])
        assertEquals(0, upgradeSavedSmartChargingCapabilities())
        val response = runBlocking {
            handler.setChargingProfile(OcppSession.Info("", point.identity), SetChargingProfileRequest(connectorId = 1, csChargingProfiles = profile(1, 8.0)))
        }
        assertEquals(SetChargingProfileStatus.Accepted, response.status)
    }

    @Test
    fun `startup replaces blank saved capabilities with Core and SmartCharging`() = fixture(createTransactions = false) {
        point.updateConfiguration { this["SupportedFeatureProfiles"] = "  " }
        assertEquals(1, upgradeSavedSmartChargingCapabilities())
        assertEquals("Core,SmartCharging", point.configuration["SupportedFeatureProfiles"])
    }

    @Test
    fun `Core-only configuration rejects Set and RemoteStart charging profiles before persistence`() = fixture(createTransactions = false) {
        point.updateConfiguration { this["SupportedFeatureProfiles"] = "Core" }
        val set = runBlocking {
            handler.setChargingProfile(OcppSession.Info("", point.identity), SetChargingProfileRequest(connectorId = 1, csChargingProfiles = profile(1, 8.0)))
        }
        assertEquals(SetChargingProfileStatus.Rejected, set.status)
        withCoreHandler { core ->
            val start = runBlocking {
                core.remoteStartTransactionRequest(
                    OcppSession.Info("", point.identity),
                    RemoteStartTransactionRequest(idTag = "test", connectorId = 1, chargingProfile = profile(2, 8.0, ChargingProfilePurposeType.TxProfile)),
                )
            }
            assertEquals(RemoteStartStopStatus.Rejected, start.status)
        }
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `low-power fractional and subsecond energy survives repeated scheduler and triggered readings`() = fixture {
        val session = sessions.first()
        session.endMeter = 0.0
        session.endMeterAt = start
        TransactionEnergyMeter.forget(session)
        for (quarter in 1..14_400) {
            val nanos = quarter * 250_000_000L
            TransactionEnergyMeter.advance(session, 1000.0, start.plusNanos(nanos), nanos)
            TransactionEnergyMeter.advance(session, 1000.0, start.plusNanos(nanos), nanos)
        }
        assertEquals(1000.0, session.endMeter, 0.000001)
        assertEquals(start.plusSeconds(3600), session.endMeterAt)
        TransactionEnergyMeter.forget(session)
    }

    @Test
    fun `meter clocks ignore stale readings and wall clock jumps and retain profile-limited power`() = fixture {
        val session = sessions.first()
        session.endMeter = 0.0
        session.endMeterAt = start
        TransactionEnergyMeter.forget(session)
        service.store(point, ports.first(), profile(1, 1.0))
        val watts = service.calculateChargingLimits(point, start).getValue(1).watts
        assertEquals(690.0, watts, 0.0001)
        TransactionEnergyMeter.advance(session, watts, start.plusMillis(500), 500_000_000)
        TransactionEnergyMeter.advance(session, watts, start.plusSeconds(86_400), 1_000_000_000)
        TransactionEnergyMeter.advance(session, watts, start.minusSeconds(86_400), 1_500_000_000)
        TransactionEnergyMeter.advance(session, watts, start, 1_000_000_000)
        assertEquals(watts * 1.5 / 3600, session.endMeter, 0.000001)
        session.endTime = start.plusSeconds(2)
        TransactionEnergyMeter.advance(session, watts, start.plusSeconds(86_401), 2_000_000_000)
        assertEquals(watts * 1.5 / 3600, session.endMeter, 0.000001)
        TransactionEnergyMeter.forget(session)
    }

    @Test
    fun `an older captured sample refreshes a stale DAO meter without recounting energy`() = fixture {
        val session = sessions.first()
        session.endMeter = 0.0
        session.endMeterAt = start
        TransactionEnergyMeter.forget(session)
        TransactionEnergyMeter.advance(session, 3600.0, start.plusSeconds(1), 1_000_000_000)
        TransactionEnergyMeter.advance(session, 3600.0, start.plusSeconds(2), 2_000_000_000)
        // A scheduler sample captured before a triggered reading can retain
        // these older DAO values until it reaches the shared meter boundary.
        session.endMeter = 1.0
        session.endMeterAt = start.plusSeconds(1)
        assertEquals(2.0, TransactionEnergyMeter.advance(session, 3600.0, start.plusMillis(1500), 1_500_000_000))
        assertEquals(2.0, session.endMeter)
        assertEquals(start.plusSeconds(2), session.endMeterAt)
        TransactionEnergyMeter.advance(session, 3600.0, start.plusSeconds(3), 3_000_000_000)
        assertEquals(3.0, session.endMeter)
        TransactionEnergyMeter.forget(session)
    }

    @Test
    fun `paused vehicle reports EV suspension without consuming offered power and can resume`() = fixture {
        val connector = ports.first()
        val session = sessions.first()
        service.store(point, connector, profile(1, 16.0))
        connector.carState = CarState.B

        val paused = service.refreshChargingLimits(point, start).getValue(1)
        assertEquals(0.0, paused.watts)
        assertEquals(11.04, connector.kw, 0.000001)
        assertEquals(ChargePointStatus.SuspendedEV, connector.calculateState())

        session.endMeter = 0.0
        session.endMeterAt = start
        TransactionEnergyMeter.forget(session)
        try {
            TransactionEnergyMeter.advance(session, paused.watts, start.plusSeconds(30), 30_000_000_000)
            assertEquals(0.0, session.endMeter)

            connector.carState = CarState.C
            val resumed = service.refreshChargingLimits(point, start.plusSeconds(30)).getValue(1)
            assertEquals(11040.0, resumed.watts, 0.000001)
            assertEquals(ChargePointStatus.Charging, connector.calculateState())
            TransactionEnergyMeter.advance(session, resumed.watts, start.plusSeconds(60), 60_000_000_000)
            assertEquals(92.0, session.endMeter, 0.000001)
        } finally {
            TransactionEnergyMeter.forget(session)
        }
    }

    @Test
    fun `paused vehicle preserves EVSE suspension for zero session or station profiles`() {
        for (purpose in listOf(ChargingProfilePurposeType.TxDefaultProfile, ChargingProfilePurposeType.ChargePointMaxProfile)) {
            fixture {
                val connector = ports.first()
                val target = if (purpose == ChargingProfilePurposeType.ChargePointMaxProfile) point.getConnector(0) else connector
                service.store(point, target, profile(1, 0.0, purpose))
                connector.carState = CarState.B

                assertEquals(0.0, service.refreshChargingLimits(point, start).getValue(1).watts)
                assertEquals(0.0, connector.kw)
                assertEquals(ChargePointStatus.SuspendedEVSE, connector.calculateState())

                connector.carState = CarState.C
                assertEquals(0.0, service.refreshChargingLimits(point, start).getValue(1).watts)
                assertEquals(ChargePointStatus.SuspendedEVSE, connector.calculateState())
            }
        }
    }

    @Test
    fun `paused vehicle preserves EVSE suspension when station hardware offers no power`() = fixture(stationKw = 0.0) {
        val connector = ports.first()
        connector.carState = CarState.B

        assertEquals(0.0, service.refreshChargingLimits(point, start).getValue(1).watts)
        assertEquals(0.0, connector.kw)
        assertEquals(ChargePointStatus.SuspendedEVSE, connector.calculateState())
    }

    @Test
    fun `paused vehicle status forecast does not reserve peer power and resuming shares fairly`() = fixture(connectors = 2) {
        service.store(point, point.getConnector(0), profile(1, 10000.0, ChargingProfilePurposeType.ChargePointMaxProfile, unit = ChargingRateUnit.W))
        ports[0].carState = CarState.B

        val paused = service.refreshChargingLimits(point, start)
        assertEquals(0.0, paused.getValue(1).watts)
        assertEquals(10000.0, paused.getValue(2).watts, 0.000001)
        assertEquals(5.0, ports[0].kw, 0.000001)
        assertEquals(10.0, ports[1].kw, 0.000001)
        assertEquals(ChargePointStatus.SuspendedEV, ports[0].calculateState())
        assertEquals(ChargePointStatus.Charging, ports[1].calculateState())
        val composite = service.compositeSchedule(point, 0, 60, ChargingRateUnit.W, start)
        assertEquals(listOf(10000.0), composite.chargingSchedulePeriod.map { it.limit })

        ports[0].carState = CarState.C
        val resumed = service.refreshChargingLimits(point, start)
        assertEquals(5000.0, resumed.getValue(1).watts, 0.000001)
        assertEquals(5000.0, resumed.getValue(2).watts, 0.000001)
        assertTrue(ports.all { it.calculateState() == ChargePointStatus.Charging })
    }

    @Test
    fun `zero charging period remains zero despite vehicle minimum charging rate`() = fixture {
        service.store(point, ports[0], profile(1, 0.0, minimum = 6.0))

        assertEquals(0.0, service.calculateChargingLimits(point, start).getValue(1).watts)
    }

    @Test
    fun `transaction profile overrides a higher stack default and station maximum still caps it`() = fixture {
        service.store(point, point.getConnector(0), profile(1, 12.0, ChargingProfilePurposeType.ChargePointMaxProfile))
        service.store(point, ports[0], profile(2, 6.0, stack = 9))
        service.store(point, ports[0], profile(3, 16.0, ChargingProfilePurposeType.TxProfile, transactionId = sessions[0].externalId))

        assertEquals(12.0, service.calculateChargingLimits(point, start).getValue(1).ampsPerPhase, 0.000001)
    }

    @Test
    fun `receiving a lower transaction stack last does not replace the higher active stack`() = fixture {
        service.store(point, ports[0], profile(1, 8.0, ChargingProfilePurposeType.TxProfile, stack = 4, transactionId = sessions[0].externalId))
        service.store(point, ports[0], profile(2, 24.0, ChargingProfilePurposeType.TxProfile, stack = 1, transactionId = sessions[0].externalId))

        assertEquals(8.0, service.calculateChargingLimits(point, start).getValue(1).ampsPerPhase, 0.000001)
        assertEquals(2, repository.list(point).size)
    }

    @Test
    fun `transaction stack validity exposes and then releases its lower stack`() = fixture {
        service.store(point, ports[0], profile(1, 16.0, ChargingProfilePurposeType.TxProfile, transactionId = sessions[0].externalId))
        service.store(
            point,
            ports[0],
            profile(2, 6.0, ChargingProfilePurposeType.TxProfile, stack = 1, transactionId = sessions[0].externalId).copy(
                validFrom = start.plusSeconds(60).atZone(ZoneOffset.UTC),
                validTo = start.plusSeconds(120).atZone(ZoneOffset.UTC),
            ),
        )

        assertEquals(16.0, service.calculateChargingLimits(point, start).getValue(1).ampsPerPhase, 0.000001)
        assertEquals(6.0, service.calculateChargingLimits(point, start.plusSeconds(60)).getValue(1).ampsPerPhase, 0.000001)
        assertEquals(16.0, service.calculateChargingLimits(point, start.plusSeconds(120)).getValue(1).ampsPerPhase, 0.000001)
    }

    @Test
    fun `connector default wins equal stack global default regardless of profile id`() = fixture(connectors = 2) {
        service.store(point, point.getConnector(0), profile(999, 8.0))
        service.store(point, ports[0], profile(1, 20.0))

        val limits = service.calculateChargingLimits(point, start)
        assertEquals(20.0, limits.getValue(1).ampsPerPhase, 0.000001)
        assertEquals(8.0, limits.getValue(2).ampsPerPhase, 0.000001)
    }

    @Test
    fun `new id replaces the existing slot instead of leaving an old fallback`() = fixture {
        service.store(point, ports[0], profile(1, 2.0))
        service.store(
            point,
            ports[0],
            profile(2, 8.0).copy(validFrom = start.plusSeconds(60).atZone(ZoneOffset.UTC)),
        )

        assertEquals(listOf(2), repository.list(point).map { it.profile.chargingProfileId })
        assertTrue(service.calculateChargingLimits(point, start).getValue(1).ampsPerPhase > 2.0)
    }

    @Test
    fun `same profile id moves scopes without retaining a second installation`() = fixture(connectors = 2) {
        service.store(point, ports[0], profile(1, 8.0))
        service.store(point, ports[1], profile(1, 16.0))

        val stored = repository.list(point)
        assertEquals(1, stored.size)
        assertEquals(2, stored.single().connectorId)
        assertEquals(16.0, service.calculateChargingLimits(point, start).getValue(2).ampsPerPhase, 0.000001)
    }

    @Test
    fun `clearing the station maximum preserves defaults and reports unknown on retry`() = fixture {
        service.store(point, point.getConnector(0), profile(1, 6.0, ChargingProfilePurposeType.ChargePointMaxProfile))
        service.store(point, ports[0], profile(2, 16.0))
        val request = ClearChargingProfileRequest(chargingProfilePurpose = ChargingProfilePurposeType.ChargePointMaxProfile)

        assertEquals(1, service.clear(point, null, request))
        assertEquals(0, service.clear(point, null, request))
        assertEquals(16.0, service.calculateChargingLimits(point, start).getValue(1).ampsPerPhase, 0.000001)
        assertEquals(listOf(2), repository.list(point).map { it.profile.chargingProfileId })
    }

    @Test
    fun `clear stack filter does not remove lower stacks`() = fixture {
        service.store(point, ports[0], profile(1, 16.0))
        service.store(point, ports[0], profile(2, 8.0, stack = 1))

        assertEquals(
            1,
            repository.delete(
                point,
                ports[0],
                ClearChargingProfileRequest(chargingProfilePurpose = ChargingProfilePurposeType.TxDefaultProfile),
                stackLevel = 1,
            ),
        )
        assertEquals(listOf(1), repository.list(point).map { it.profile.chargingProfileId })
    }

    @Test
    fun `two charging connectors share one station power budget`() = fixture(connectors = 2) {
        service.store(point, point.getConnector(0), profile(1, 10000.0, ChargingProfilePurposeType.ChargePointMaxProfile, unit = ChargingRateUnit.W))

        val limits = service.calculateChargingLimits(point, start)
        assertEquals(10000.0, limits.values.sumOf { it.watts }, 0.000001)
        assertTrue(limits.values.all { it.watts > 0.0 && it.watts <= 10000.0 })
    }

    @Test
    fun `station current budget is shared per phase rather than duplicated per connector`() = fixture(connectors = 2) {
        service.store(point, point.getConnector(0), profile(1, 16.0, ChargingProfilePurposeType.ChargePointMaxProfile))

        val limits = service.calculateChargingLimits(point, start)
        assertEquals(16.0, limits.values.sumOf { it.ampsPerPhase }, 0.000001)
        assertEquals(11040.0, limits.values.sumOf { it.watts }, 0.000001)
    }

    @Test
    fun `current profile keeps its per-phase limit when the vehicle charges on one phase`() = fixture {
        ports[0].vehicleNumberPhases = 1
        service.store(point, ports[0], profile(1, 16.0, phases = 3))

        val limit = service.calculateChargingLimits(point, start).getValue(1)
        assertEquals(1, limit.numberPhases)
        assertEquals(16.0, limit.ampsPerPhase, 0.000001)
        assertEquals(3680.0, limit.watts, 0.000001)
    }

    @Test
    fun `unused allowance on a smaller connector remains available to another connector`() = fixture(connectors = 2) {
        ports[0].maxKw = 2.0
        service.store(point, point.getConnector(0), profile(1, 10000.0, ChargingProfilePurposeType.ChargePointMaxProfile, unit = ChargingRateUnit.W))

        val limits = service.calculateChargingLimits(point, start)
        assertTrue(limits.getValue(1).watts <= 2000.0)
        assertEquals(10000.0, limits.values.sumOf { it.watts }, 0.000001)
    }

    @Test
    fun `composite schedule contains future changes and respects local hardware`() = fixture {
        ports[0].maxKw = 11.0
        service.store(point, ports[0], profile(1, 8.0, periods = listOf(0 to 8.0, 60 to 32.0)))

        val schedule = service.compositeSchedule(point, 1, 120, ChargingRateUnit.W, start)
        assertEquals(start, schedule.startSchedule?.toInstant())
        assertEquals(120, schedule.duration)
        assertEquals(listOf(0, 60), schedule.chargingSchedulePeriod.map { it.startPeriod })
        assertEquals(5520.0, schedule.chargingSchedulePeriod[0].limit)
        assertEquals(11000.0, schedule.chargingSchedulePeriod[1].limit)
    }

    @Test
    fun `connector zero composite reports expected grid consumption not merely the station ceiling`() = fixture(connectors = 2) {
        service.store(point, point.getConnector(0), profile(1, 6000.0, ChargingProfilePurposeType.ChargePointMaxProfile, unit = ChargingRateUnit.W))
        service.store(point, point.getConnector(0), profile(2, 1000.0, unit = ChargingRateUnit.W, periods = listOf(0 to 1000.0, 60 to 2000.0)))

        val schedule = service.compositeSchedule(point, 0, 120, ChargingRateUnit.W, start)
        assertEquals(listOf(0, 60), schedule.chargingSchedulePeriod.map { it.startPeriod })
        assertEquals(listOf(2000.0, 4000.0), schedule.chargingSchedulePeriod.map { it.limit })
    }

    @Test
    fun `global relative default transitions use the physical transaction start`() = fixture {
        val relative = profile(1, 16.0, periods = listOf(0 to 16.0, 3600 to 6.0))
        service.store(
            point,
            point.getConnector(0),
            relative.copy(
                chargingProfileKind = ChargingProfileKind.Relative,
                chargingSchedule = relative.chargingSchedule!!.copy(startSchedule = null),
            ),
        )
        val origin = start.plusSeconds(1800)

        val schedule = service.compositeSchedule(point, 1, 2400, ChargingRateUnit.A, origin)

        assertEquals(origin, schedule.startSchedule?.toInstant())
        assertEquals(listOf(0, 1800), schedule.chargingSchedulePeriod.map { it.startPeriod })
        assertEquals(listOf(16.0, 6.0), schedule.chargingSchedulePeriod.map { it.limit })
    }

    @Test
    fun `fractional validity edges conservatively bound every represented second`() = fixture {
        service.store(point, ports[0], profile(1, 20.0))
        service.store(
            point,
            ports[0],
            profile(2, 6.0, stack = 1).copy(
                validFrom = start.plusMillis(1200).atZone(ZoneOffset.UTC),
                validTo = start.plusMillis(3200).atZone(ZoneOffset.UTC),
            ),
        )
        val origin = start.plusMillis(500)

        val schedule = service.compositeSchedule(point, 1, 6, ChargingRateUnit.A, origin)

        assertEquals(origin, schedule.startSchedule?.toInstant())
        assertEquals(listOf(0, 3), schedule.chargingSchedulePeriod.map { it.startPeriod })
        assertEquals(listOf(6.0, 20.0), schedule.chargingSchedulePeriod.map { it.limit })
    }

    @Test
    fun `fractional station period edges lower the earlier bucket and raise the later bucket`() = fixture {
        val station = profile(
            1,
            16.0,
            ChargingProfilePurposeType.ChargePointMaxProfile,
            periods = listOf(0 to 16.0, 1 to 6.0, 3 to 16.0),
        )
        service.store(
            point,
            point.getConnector(0),
            station.copy(
                chargingSchedule = station.chargingSchedule!!.copy(
                    startSchedule = start.plusMillis(200).atZone(ZoneOffset.UTC),
                ),
            ),
        )
        val origin = start.plusMillis(500)

        val schedule = service.compositeSchedule(point, 1, 6, ChargingRateUnit.A, origin)

        assertEquals(origin, schedule.startSchedule?.toInstant())
        assertEquals(listOf(0, 3), schedule.chargingSchedulePeriod.map { it.startPeriod })
        assertEquals(listOf(6.0, 16.0), schedule.chargingSchedulePeriod.map { it.limit })
    }

    @Test
    fun `legacy remote-start profile participates beside newly stored transaction stacks`() = fixture {
        sessions[0].chargingProfile = profile(1, 6.0, ChargingProfilePurposeType.TxProfile, stack = 4, transactionId = sessions[0].externalId)
        service.store(point, ports[0], profile(2, 16.0, ChargingProfilePurposeType.TxProfile, stack = 1, transactionId = sessions[0].externalId))

        assertEquals(6.0, service.calculateChargingLimits(point, start).getValue(1).ampsPerPhase, 0.000001)
    }

    @Test
    fun `transaction end removes its profiles while retaining reusable defaults`() = fixture {
        service.store(point, ports[0], profile(1, 16.0))
        service.store(point, ports[0], profile(2, 6.0, ChargingProfilePurposeType.TxProfile, transactionId = sessions[0].externalId))
        service.deleteTransactionProfiles(point, sessions[0].externalId)
        sessions[0].endTime = start.plusSeconds(30)
        ports[0].activeTransaction = null

        assertEquals(listOf(1), repository.list(point).map { it.profile.chargingProfileId })
        assertFalse(repository.list(point).any { it.profile.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile })
    }

    @Test
    fun `wire handler rejects a transaction profile sent to another connector`() = fixture(connectors = 2) {
        val result = runBlocking {
            handler.setChargingProfile(
                OcppSession.Info("", point.identity),
                SetChargingProfileRequest(
                    connectorId = 2,
                    csChargingProfiles = profile(1, 8.0, ChargingProfilePurposeType.TxProfile, transactionId = sessions[0].externalId),
                ),
            )
        }

        assertEquals(SetChargingProfileStatus.Rejected, result.status)
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `set transaction profile requires its active transaction id`() = fixture {
        val response = runBlocking {
            handler.setChargingProfile(
                OcppSession.Info("", point.identity),
                SetChargingProfileRequest(
                    connectorId = 1,
                    csChargingProfiles = profile(1, 8.0, ChargingProfilePurposeType.TxProfile),
                ),
            )
        }

        assertEquals(SetChargingProfileStatus.Rejected, response.status)
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `remote start rejects nontransaction profiles and a prematurely assigned transaction id`() = fixture(createTransactions = false) {
        withCoreHandler { core ->
            val invalid = listOf(
                profile(1, 8.0),
                profile(2, 8.0, ChargingProfilePurposeType.ChargePointMaxProfile),
                profile(3, 8.0, ChargingProfilePurposeType.TxProfile, transactionId = 500),
            )
            for (profile in invalid) {
                val response = runBlocking {
                    core.remoteStartTransactionRequest(
                        OcppSession.Info("", point.identity),
                        RemoteStartTransactionRequest(idTag = "test", connectorId = 1, chargingProfile = profile),
                    )
                }
                assertEquals(RemoteStartStopStatus.Rejected, response.status)
            }
        }
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `remote start cannot bypass the advertised schedule capabilities`() = fixture(createTransactions = false) {
        point.updateConfiguration {
            this["ChargeProfileMaxStackLevel"] = "1"
            this["ChargingScheduleMaxPeriods"] = "1"
            this["ChargingScheduleAllowedChargingRateUnit"] = "Current"
        }
        withCoreHandler { core ->
            val invalid = listOf(
                profile(1, 8.0, ChargingProfilePurposeType.TxProfile, stack = 2),
                profile(2, 8.0, ChargingProfilePurposeType.TxProfile, periods = listOf(0 to 8.0, 60 to 16.0)),
                profile(3, 1000.0, ChargingProfilePurposeType.TxProfile, unit = ChargingRateUnit.W),
            )
            for (profile in invalid) {
                val response = runBlocking {
                    core.remoteStartTransactionRequest(
                        OcppSession.Info("", point.identity),
                        RemoteStartTransactionRequest(idTag = "test", connectorId = 1, chargingProfile = profile),
                    )
                }
                assertEquals(RemoteStartStopStatus.Rejected, response.status, "Profile ${profile.chargingProfileId}")
            }
        }
        assertTrue(repository.list(point).isEmpty())
    }

    @Test
    fun `composite handler returns an accepted station forecast with a schedule origin`() = fixture(connectors = 2) {
        val default = profile(1, 1000.0, unit = ChargingRateUnit.W)
        service.store(
            point,
            point.getConnector(0),
            default.copy(
                chargingSchedule = default.chargingSchedule!!.copy(startSchedule = Instant.EPOCH.atZone(ZoneOffset.UTC)),
            ),
        )
        val response = runBlocking {
            handler.getCompositeSchedule(
                OcppSession.Info("", point.identity),
                GetCompositeScheduleRequest(connectorId = 0, duration = 60, chargingRateUnit = ChargingRateUnit.W),
            )
        }

        assertEquals(GetCompositeScheduleStatus.Accepted, response.status)
        assertEquals(0, response.connectorId)
        val scheduleStart = assertNotNull(response.scheduleStart)
        val schedule = assertNotNull(response.chargingSchedule)
        assertEquals(scheduleStart, schedule.startSchedule)
        assertEquals(0, schedule.chargingSchedulePeriod.first().startPeriod)
        assertEquals(2000.0, schedule.chargingSchedulePeriod.first().limit)
    }

    @Test
    fun `wire clear removes a station profile and subsequently reports unknown`() = fixture {
        service.store(point, point.getConnector(0), profile(1, 6.0, ChargingProfilePurposeType.ChargePointMaxProfile))
        val request = ClearChargingProfileRequest(id = 1, connectorId = 0)
        val first = runBlocking { handler.clearChargingProfile(OcppSession.Info("", point.identity), request) }
        val second = runBlocking { handler.clearChargingProfile(OcppSession.Info("", point.identity), request) }

        assertEquals(ClearChargingProfileStatus.Accepted, first.status)
        assertEquals(ClearChargingProfileStatus.Unknown, second.status)
    }

    @Test
    fun `clear wire preserves stack filter and echoes request id`() = fixture {
        service.store(point, ports[0], profile(1, 16.0))
        service.store(point, ports[0], profile(2, 8.0, stack = 1))
        val request = """[2,"clear-stack","ClearChargingProfile",{"connectorId":1,"stackLevel":1}]"""

        suspend fun clear() = ClearChargingProfileWireHandler.handle(request) { payload, stack ->
            handler.clearChargingProfile(OcppSession.Info("", point.identity), payload, stack)
        }
        val first = MontaSerialization.objectMapper.readTree(runBlocking { clear() })
        val second = MontaSerialization.objectMapper.readTree(runBlocking { clear() })

        assertEquals(3, first[0].intValue())
        assertEquals("clear-stack", first[1].textValue())
        assertEquals("Accepted", first[2]["status"].textValue())
        assertEquals("Unknown", second[2]["status"].textValue())
        assertEquals(listOf(1), repository.list(point).map { it.profile.chargingProfileId })
    }

    @Test
    fun `clear wire rejects a negative stack without changing profiles`() = fixture {
        service.store(point, ports[0], profile(1, 16.0))
        var called = false
        val response = runBlocking {
            ClearChargingProfileWireHandler.handle(
                """[2,"negative-stack","ClearChargingProfile",{"stackLevel":-1}]""",
            ) { payload, stack ->
                called = true
                handler.clearChargingProfile(OcppSession.Info("", point.identity), payload, stack)
            }
        }
        val frame = MontaSerialization.objectMapper.readTree(response)

        assertFalse(called)
        assertEquals(4, frame[0].intValue())
        assertEquals("negative-stack", frame[1].textValue())
        assertEquals("PropertyConstraintViolation", frame[2].textValue())
        assertEquals(1, repository.list(point).size)
    }

    @Test
    fun `clear wire rejects a noninteger stack before calling handler`() = fixture {
        var called = false
        val response = runBlocking {
            ClearChargingProfileWireHandler.handle(
                """[2,"string-stack","ClearChargingProfile",{"stackLevel":"1"}]""",
            ) { payload, stack ->
                called = true
                handler.clearChargingProfile(OcppSession.Info("", point.identity), payload, stack)
            }
        }

        assertFalse(called)
        assertEquals("TypeConstraintViolation", MontaSerialization.objectMapper.readTree(response)[2].textValue())
    }

    @Test
    fun `unknown clear property cannot accidentally turn a filtered request into clearing every profile`() = fixture {
        service.store(point, ports[0], profile(1, 16.0))
        var called = false
        val response = runBlocking {
            ClearChargingProfileWireHandler.handle(
                """[2,"misspelled-stack","ClearChargingProfile",{"stacklevel":1}]""",
            ) { payload, stack ->
                called = true
                handler.clearChargingProfile(OcppSession.Info("", point.identity), payload, stack)
            }
        }
        val frame = MontaSerialization.objectMapper.readTree(response)

        assertFalse(called)
        assertEquals(4, frame[0].intValue())
        assertEquals("misspelled-stack", frame[1].textValue())
        assertEquals("PropertyConstraintViolation", frame[2].textValue())
        assertEquals(listOf(1), repository.list(point).map { it.profile.chargingProfileId })
    }

    @Test
    fun `clear wire rejects coerced call message types before clearing profiles`() = fixture {
        service.store(point, ports[0], profile(1, 16.0))
        for (messageType in listOf("\"2\"", "2.5")) {
            var called = false
            val response = runBlocking {
                ClearChargingProfileWireHandler.handle(
                    """[$messageType,"bad-type","ClearChargingProfile",{}]""",
                ) { payload, stack ->
                    called = true
                    handler.clearChargingProfile(OcppSession.Info("", point.identity), payload, stack)
                }
            }
            val frame = MontaSerialization.objectMapper.readTree(response)

            assertFalse(called)
            assertEquals(4, frame[0].intValue())
            assertEquals("FormationViolation", frame[2].textValue())
        }
        assertEquals(listOf(1), repository.list(point).map { it.profile.chargingProfileId })
    }

    @Test
    fun `clear wire leaves unrelated requests to the regular dispatcher`() = fixture {
        val response = runBlocking {
            ClearChargingProfileWireHandler.handle("""[2,"heartbeat","Heartbeat",{}]""") { _, _ ->
                error("An unrelated request must not invoke clearing")
            }
        }

        assertNull(response)
    }
}

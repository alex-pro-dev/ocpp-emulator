package com.monta.ocpp.emulator.v16.connection

import com.fasterxml.jackson.databind.JsonNode
import com.monta.ocpp.emulator.CommonKoinModule
import com.monta.ocpp.emulator.MainModule
import com.monta.ocpp.emulator.MontaKoinModule
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointTable
import com.monta.ocpp.emulator.chargepoint.model.MeterType
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.chargepointconnector.entity.ChargePointConnectorTable
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransaction
import com.monta.ocpp.emulator.common.util.MontaSerialization
import com.monta.ocpp.emulator.v16.data.entity.TxDefault
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.ksp.generated.module
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionLifecycleTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `failed handshake is stopped before edited settings connect and successful session stays single`() = runBlocking {
        val database = Database.connect("jdbc:sqlite:${directory.resolve("connections.db")}", driver = "org.sqlite.JDBC")
        transaction(database) { SchemaUtils.create(ChargePointTable, ChargePointConnectorTable, ChargePointTransaction, TxDefault) }
        val koin = startKoin { modules(CommonKoinModule().module, MontaKoinModule().module, MainModule.module) }.koin
        val service = koin.get<ChargePointService>()
        val manager = koin.get<ConnectionManager>()
        LoopbackPeer().use { peer ->
            try {
                withTimeout(20_000) {
                    val chargePoint = service.upsert(
                        name = "Connection regression",
                        identity = "Connection-Test",
                        password = null,
                        ocppUrl = "${peer.url}/old",
                        apiUrl = "http://example.invalid",
                        firmware = "test",
                        maxKw = 22.0,
                        connectorCount = 1,
                        meterType = MeterType.OCPP,
                    )
                    val id = chargePoint.id.value
                    repeat(20) { manager.connect(id) }
                    assertEquals("/old/Connection-Test", peer.requests.receive())
                    // This waits for the failed handshake/backoff job to finish, not just connected=false.
                    manager.disconnect(id).join()
                    assertEquals(1, peer.handshakes.get())
                    val route = "16j"
                    service.update(service.getById(id)) { ocppUrl = "${peer.url}/ocpp/$route" }
                    repeat(20) { manager.connect(id) }
                    assertEquals("/ocpp/$route/Connection-Test", peer.requests.receive())
                    peer.awaitStatus()
                    delay(2_300)
                    assertEquals(2, peer.handshakes.get(), "old failed clients must not retry after replacement")
                    assertTrue(service.getById(id).connected)

                    // A real peer close should create exactly one retry after cleaning up the old socket.
                    peer.dropConnections()
                    assertEquals("/ocpp/$route/Connection-Test", peer.requests.receive())
                    while (!service.getById(id).connected) delay(10)
                    assertEquals(3, peer.handshakes.get())

                    service.update(service.getById(id)) { ocppUrl = "${peer.url}/edited/ocpp/$route/Connection-Test?tenant=example" }
                    manager.reconnect(id, 0)
                    assertEquals("/edited/ocpp/$route/Connection-Test?tenant=example", peer.requests.receive())
                    peer.awaitStatus()
                    manager.disconnect(id).join()
                    delay(2_300)
                    assertEquals(4, peer.handshakes.get(), "manual disconnect must stop retries")
                    assertFalse(service.getById(id).connected)
                }
            } finally {
                manager.disconnectAll()
                stopKoin()
                TransactionManager.closeAndUnregister(database)
            }
        }
    }

    @Test
    fun silentPeerDisconnectIsBoundedAndReleasesSocketAndProtocolSession() = runBlocking {
        val database = Database.connect("jdbc:sqlite:${directory.resolve("silent.db")}", driver = "org.sqlite.JDBC")
        transaction(database) { SchemaUtils.create(ChargePointTable, ChargePointConnectorTable, ChargePointTransaction, TxDefault) }
        val koin = startKoin { modules(CommonKoinModule().module, MontaKoinModule().module, MainModule.module) }.koin
        val service = koin.get<ChargePointService>()
        val manager = koin.get<ConnectionManager>()
        LoopbackPeer().use { peer ->
            try {
                withTimeout(15_000) {
                    val point = service.upsert("Silent peer", "SILENT-TEST", null, "${peer.url}/ocpp/16j", "http://example.invalid", "test", 22.0, 1, MeterType.OCPP)
                    val id = point.id.value
                    manager.connect(id)
                    peer.awaitStatus()
                    peer.silent = true
                    withTimeout(4_000) { manager.disconnect(id).join() }
                    assertFalse(service.getById(id).connected)
                    while (peer.socketCount != 0) delay(10)
                    val client = koin.get<com.monta.library.ocpp.v16.client.OcppClientV16>()
                    assertTrue(runCatching { client.getSessionByIdentity("SILENT-TEST") }.isFailure)
                    delay(2_300)
                    assertEquals(1, peer.handshakes.get(), "teardown must cancel the retry and scheduler owners")
                    manager.disconnectAll()
                    manager.connect(id)
                    delay(100)
                    assertEquals(1, peer.handshakes.get(), "shutdown rejects new connection work")
                }
            } finally {
                manager.disconnectAll()
                stopKoin()
                TransactionManager.closeAndUnregister(database)
            }
        }
    }

    @Test
    fun `stations connect concurrently with separate connector counts`() = runBlocking {
        val database = Database.connect("jdbc:sqlite:${directory.resolve("stations.db")}", driver = "org.sqlite.JDBC")
        transaction(database) { SchemaUtils.create(ChargePointTable, ChargePointConnectorTable, ChargePointTransaction, TxDefault) }
        val koin = startKoin { modules(CommonKoinModule().module, MontaKoinModule().module, MainModule.module) }.koin
        val service = koin.get<ChargePointService>()
        val manager = koin.get<ConnectionManager>()
        LoopbackPeer().use { peer ->
            LoopbackPeer().use { otherPeer ->
                try {
                    withTimeout(20_000) {
                        val cases = listOf(peer to 1, otherPeer to 4)
                        val stations = cases.map { (server, count) ->
                            service.upsert(
                                name = "Station $count",
                                identity = "Station-$count",
                                password = null,
                                ocppUrl = server.url,
                                apiUrl = "",
                                firmware = "test",
                                maxKw = 22.0,
                                connectorCount = count,
                                meterType = MeterType.OCPP,
                            )
                        }
                        stations.forEach { manager.connect(it.id.value) }
                        cases.zip(stations).forEach { (case, station) ->
                            val (server, count) = case
                            assertEquals("/Station-$count", server.requests.receive())
                            val seen = mutableSetOf<Int>()
                            while (seen.size < count) {
                                val connectorId = server.awaitCall("StatusNotification").path(3).path("connectorId").asInt()
                                if (connectorId != 0) seen.add(connectorId)
                            }
                            assertEquals((1..count).toSet(), seen)
                            assertTrue(service.getById(station.id.value).connected)
                        }
                    }
                } finally {
                    manager.disconnectAll()
                    stopKoin()
                    TransactionManager.closeAndUnregister(database)
                }
            }
        }
    }

    /** Owned loopback WebSocket peer; no real charger, service or user's database is involved. */
    private class LoopbackPeer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val mapper = MontaSerialization.objectMapper
        val url = "ws://127.0.0.1:${server.localPort}"

        @Volatile
        var silent = false
        val socketCount: Int get() = sockets.size
        val handshakes = AtomicInteger()
        val requests = Channel<String>(Channel.UNLIMITED)
        private val actions = Channel<String>(Channel.UNLIMITED)
        private val calls = Channel<JsonNode>(Channel.UNLIMITED)
        private val responses = Channel<JsonNode>(Channel.UNLIMITED)
        val starts = AtomicInteger()
        val authorizations = AtomicInteger()

        init {
            thread(isDaemon = true, name = "emulator-test-accept") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets.add(socket)
                    thread(isDaemon = true, name = "emulator-test-peer") {
                        socket.use {
                            try {
                                serve(socket)
                            } finally {
                                sockets.remove(socket)
                            }
                        }
                    }
                }
            }
        }

        suspend fun awaitStatus() {
            while (actions.receive() != "StatusNotification") {
                // Wait until boot completed and the connector status round trip was sent.
            }
        }

        fun sendCommand(
            id: String,
            action: String,
            payload: String,
        ) {
            writeFrame(sockets.single(), """[2,"$id","$action",$payload]""".toByteArray())
        }

        suspend fun awaitCall(
            action: String,
        ): JsonNode {
            while (true) {
                val frame = calls.receive()
                if (frame.path(2).asText() == action) return frame
            }
        }

        suspend fun awaitResponse(
            id: String,
        ): JsonNode {
            while (true) {
                val frame = responses.receive()
                if (frame.path(1).asText() == id) return frame
            }
        }

        private fun writeFrame(
            socket: Socket,
            response: ByteArray,
        ) {
            synchronized(socket) {
                val output = socket.getOutputStream()
                output.write(0x81)
                if (response.size < 126) {
                    output.write(response.size)
                } else {
                    output.write(126)
                    output.write(response.size shr 8)
                    output.write(response.size and 255)
                }
                output.write(response)
                output.flush()
            }
        }

        private fun serve(
            socket: Socket,
        ) {
            try {
                val input = DataInputStream(socket.getInputStream())
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) headers.append(input.readUnsignedByte().toChar())
                val lines = headers.toString().split("\r\n")
                val path = lines.first().split(' ')[1]
                handshakes.incrementAndGet()
                if (path.startsWith("/old/")) {
                    socket.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    requests.trySend(path)
                    return
                }
                val key = lines.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val protocol = lines.first { it.startsWith("Sec-WebSocket-Protocol:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
                )
                socket.getOutputStream().write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: $protocol\r\n\r\n"
                        ).toByteArray(),
                )
                requests.trySend(path)
                while (!socket.isClosed) {
                    val opcode = input.readUnsignedByte() and 15
                    val lengthByte = input.readUnsignedByte()
                    val length = when (val size = lengthByte and 127) {
                        126 -> input.readUnsignedShort()
                        127 -> input.readLong().toInt()
                        else -> size
                    }
                    require(length in 0..65_536)
                    val mask = if (lengthByte and 128 != 0) input.readNBytes(4) else null
                    val payload = input.readNBytes(length)
                    if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                    if (opcode == 8) return
                    if (opcode != 1) continue
                    val frame = mapper.readTree(payload)
                    if (frame.path(0).asInt() != 2) {
                        responses.trySend(frame)
                        continue
                    }
                    calls.trySend(frame)
                    val action = frame.path(2).asText()
                    if (action == "Authorize") authorizations.incrementAndGet()
                    if (action == "StartTransaction") starts.incrementAndGet()
                    if (silent) continue
                    val result = if (action == "BootNotification") {
                        "{\"status\":\"Accepted\",\"currentTime\":\"2026-09-18T12:00:00Z\",\"interval\":3600}"
                    } else if (action == "Authorize") {
                        "{\"idTagInfo\":{\"status\":\"Accepted\"}}"
                    } else if (action == "StartTransaction") {
                        "{\"transactionId\":1234,\"idTagInfo\":{\"status\":\"Accepted\"}}"
                    } else if (action == "DataTransfer") {
                        "{\"status\":\"Accepted\"}"
                    } else {
                        "{}"
                    }
                    val response = "[3,\"${frame.path(1).asText()}\",$result]".toByteArray()
                    writeFrame(socket, response)
                    actions.trySend(action)
                }
            } catch (_: java.io.IOException) {
                // Expected when the test disconnects or drops the peer.
            }
        }

        fun dropConnections() {
            sockets.forEach { it.close() }
        }

        override fun close() {
            server.close()
            dropConnections()
        }
    }
}

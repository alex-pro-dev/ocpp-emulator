package com.monta.ocpp.emulator.v16.connection

import com.monta.library.ocpp.common.session.OcppSessionRepository
import com.monta.library.ocpp.v16.client.OcppClientV16
import com.monta.library.ocpp.v16.core.ChargePointStatus
import com.monta.library.ocpp.v16.core.StatusNotificationRequest
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepoint.model.stationWebSocketUrl
import com.monta.ocpp.emulator.chargepoint.service.ChargePointService
import com.monta.ocpp.emulator.common.util.MontaSerialization
import com.monta.ocpp.emulator.common.util.injectAnywhere
import com.monta.ocpp.emulator.interceptor.MessageInterceptor
import com.monta.ocpp.emulator.logger.GlobalLogger
import com.monta.ocpp.emulator.v16.ChargePointManager
import com.monta.ocpp.emulator.v16.data.service.TxDefaultService
import com.monta.ocpp.emulator.v16.profile.ClearChargingProfileWireHandler
import com.monta.ocpp.emulator.v16.profile.SmartChargingClientHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.util.collections.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class ChargePointConnection(
    val chargePointId: Long,
) {
    private val logger = KotlinLogging.logger {}
    private val chargePointService: ChargePointService by injectAnywhere()
    private val ocppClientV16: OcppClientV16 by injectAnywhere()
    private val sessionRepository: OcppSessionRepository by injectAnywhere()
    private val interceptor: MessageInterceptor by injectAnywhere()
    private val smartChargingHandler: SmartChargingClientHandler by injectAnywhere()
    private val txDefaultService: TxDefaultService by injectAnywhere()
    private val chargePointManager: ChargePointManager by injectAnywhere()
    private val reconnect = AtomicBoolean(true)

    @Volatile
    private var websocketSession: WebSocketSession? = null

    private var activeChargePoint: ChargePointDAO? = null
    private var activeIdentity: String? = null
    private var protocolRegistered = false
    private val client by lazy {
        HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 20.seconds
            }
        }
    }
    private var connectionAttempts = 1
    private val requestIdMap = ConcurrentMap<String, Long>()
    private val totalLatencyNanos = AtomicLong(0L)
    private val messageCount = AtomicInteger(0)

    val chargePoint: ChargePointDAO
        get() = chargePointService.getById(chargePointId)

    suspend fun connect(
        isReconnecting: Boolean = false,
    ) {
        var retry = isReconnecting
        try {
            while (reconnect.get()) {
                currentCoroutineContext().ensureActive()
                logger.info { "Connecting chargePointId=$chargePointId (isReconnecting=$retry)" }
                var isAuthError = false
                var additionalInfo: String? = null
                var shouldReconnect = true
                try {
                    createConnection(retry)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: WebSocketException) {
                    isAuthError = exception.message?.contains("401") == true
                    additionalInfo = if (isAuthError) null else exception.message
                } catch (exception: Exception) {
                    logger.warn(exception) { "Error connecting chargePointId=$chargePointId" }
                } finally {
                    withContext(NonCancellable) {
                        shouldReconnect = releaseSession()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!reconnect.get() || !shouldReconnect) break
                val backOffTime = getBackoffTime()
                val errorMessage = when {
                    isAuthError -> "Charge point failed to authenticate; trying again in ${backOffTime}s"
                    additionalInfo != null -> "Unable to connect to server because of $additionalInfo, trying again in ${backOffTime}s"
                    else -> "Unable to connect to server, trying again in ${backOffTime}s"
                }
                GlobalLogger.warn(chargePoint, errorMessage)
                delay(backOffTime * 1000L)
                retry = true
            }
        } finally {
            // Never reuse a closed client; a replacement starts after this job has joined.
            client.close()
        }
    }

    private suspend fun createConnection(
        isReconnecting: Boolean,
    ) {
        // Capture the socket settings once, including the identity used during teardown.
        val chargePoint = this.chargePoint
        val identity = chargePoint.identity
        val configuredUrl = chargePoint.ocppUrl
        val password = chargePoint.basicAuthPassword
        activeChargePoint = chargePoint
        activeIdentity = identity
        client.webSocket(
            request = {
                password?.let { basicAuth(identity, it) }
                url(stationWebSocketUrl(configuredUrl, identity))
                header("Sec-WebSocket-Protocol", "ocpp1.6")
            },
        ) {
            currentCoroutineContext().ensureActive()
            if (!reconnect.get()) return@webSocket
            websocketSession = this
            protocolRegistered = true
            ocppClientV16.connect(
                identity = identity,
                isReconnecting = isReconnecting,
                sendFrame = { message ->
                    GlobalLogger.logSend(chargePoint, message)
                    this.send(message)
                    logLatency(message)
                },
                closeConnection = { reason ->
                    this.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                },
            )
            connectionAttempts = 0
            chargePointService.update(chargePoint) { connected = true }
            val bootJob = launch {
                if (chargePoint.bootedAt == null) chargePointManager.startBootSequence(chargePoint)
            }
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val message = frame.readText()
                            val newMessage = interceptor.intercept(identity, message)
                            GlobalLogger.logReceive(chargePoint, message)
                            if (newMessage != null) {
                                val clearResponse = ClearChargingProfileWireHandler.handle(newMessage) { request, stackLevel ->
                                    smartChargingHandler.clearChargingProfile(chargePoint.sessionInfo, request, stackLevel)
                                }
                                if (clearResponse == null) {
                                    ocppClientV16.receiveMessage(identity, newMessage)
                                } else {
                                    val response = interceptor.intercept(identity, clearResponse)
                                    if (response != null) {
                                        GlobalLogger.logSend(chargePoint, response)
                                        send(response)
                                        logLatency(response)
                                    }
                                }
                            }
                            logLatency(message)
                        }

                        else -> GlobalLogger.warn(chargePoint, "unknown frame $frame")
                    }
                }
            } finally {
                withContext(NonCancellable) { bootJob.cancelAndJoin() }
            }
        }
    }

    private suspend fun logLatency(
        websocketMessage: String,
    ) {
        val jsonNode = MontaSerialization.objectMapper.readTree(websocketMessage)
        val requestId = jsonNode.get(1).asText()
        val timestamp = requestIdMap.remove(requestId)
        if (timestamp != null) {
            val total = totalLatencyNanos.addAndGet(System.nanoTime() - timestamp)
            val messages = messageCount.incrementAndGet()
            chargePointService.update(chargePoint) {
                messageCount = messages
                averageLatencyMillis = Duration.ofNanos(total / messages).toMillis()
            }
        } else {
            requestIdMap[requestId] = System.nanoTime()
        }
    }

    suspend fun disconnect(
        closeReason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, ""),
    ) {
        // Cancellation and a failed handshake must never enable retries again.
        reconnect.set(false)
        val chargePoint = activeChargePoint ?: this.chargePoint
        try {
            txDefaultService.cancelPendingRemoteStarts(chargePoint)
            if (websocketSession != null) {
                ocppClientV16.sendMessage(
                    activeIdentity ?: chargePoint.identity,
                    StatusNotificationRequest(
                        connectorId = 0,
                        errorCode = chargePoint.errorCode,
                        info = "Disconnecting",
                        status = ChargePointStatus.Unavailable,
                    ),
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(exception) { "Failed to notify disconnect for chargePointId=$chargePointId" }
        } finally {
            withContext(NonCancellable) {
                chargePointService.update(chargePoint) {
                    status = ChargePointStatus.Unavailable
                    connected = false
                }
                val session = websocketSession
                try {
                    withTimeoutOrNull(500) { session?.close(closeReason) }
                } finally {
                    session?.coroutineContext?.cancel()
                }
            }
        }
    }

    private suspend fun releaseSession(): Boolean {
        val chargePoint = activeChargePoint ?: return true
        val identity = activeIdentity ?: chargePoint.identity
        var shouldReconnect = true
        try {
            txDefaultService.cancelPendingRemoteStarts(chargePoint)
            if (protocolRegistered) {
                // onDisconnect is only a callback in library-ocpp 1.0.8. Remove and
                // close the session too, including its protocol send channel.
                val session = sessionRepository.remove(identity)
                try {
                    withTimeoutOrNull(500) { session?.close("Disconnected") }
                } finally {
                    withTimeoutOrNull(1_000) { shouldReconnect = ocppClientV16.onDisconnect(identity) }
                }
            }
        } catch (exception: Exception) {
            logger.warn(exception) { "Failed to release protocol session for chargePointId=$chargePointId" }
        } finally {
            protocolRegistered = false
            websocketSession?.coroutineContext?.cancel()
            websocketSession = null
            activeChargePoint = null
            activeIdentity = null
            requestIdMap.clear()
            interceptor.clearPending(identity)
            chargePointService.update(chargePoint) { connected = false }
        }
        return shouldReconnect
    }

    private fun getBackoffTime(): Int {
        val attempts = connectionAttempts++
        return min(60, max(2.0.pow(attempts.toDouble()).roundToInt(), 1))
    }
}

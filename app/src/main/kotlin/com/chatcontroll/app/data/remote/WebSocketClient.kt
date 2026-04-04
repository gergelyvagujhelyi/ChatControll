package com.chatcontroll.app.data.remote

import com.chatcontroll.app.BuildConfig
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.remote.dto.CallSignalDto
import com.chatcontroll.app.domain.repository.MessageRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket client for real-time message delivery signals.
 *
 * Connects to the relay server's /v1/ws endpoint and listens for
 * new-message signals. When a signal arrives, it triggers a fetch
 * of pending encrypted envelopes via the REST API.
 *
 * The WebSocket never carries message content — only delivery signals.
 */
@Singleton
class WebSocketClient @Inject constructor(
    private val keyManager: KeyManager,
    private val messageRepository: dagger.Lazy<MessageRepository>,
) {
    private val logger = Logger.getLogger("WebSocketClient")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingIntervalMillis = 30_000
        }
    }

    private val _incomingCallSignals = MutableSharedFlow<CallSignalDto>(extraBufferCapacity = 16)
    val incomingCallSignals: SharedFlow<CallSignalDto> = _incomingCallSignals.asSharedFlow()

    private var connectionJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY

    fun connect() {
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            while (isActive) {
                try {
                    connectWebSocket()
                } catch (e: Exception) {
                    logger.warning("WebSocket connection failed: ${e.message}")
                }

                // Exponential backoff reconnect
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        reconnectDelay = INITIAL_RECONNECT_DELAY
        client.close()
    }

    private suspend fun connectWebSocket() {
        val userId = keyManager.getUserId() ?: return
        val baseUrl = BuildConfig.API_BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        client.webSocket("$baseUrl/v1/ws") {
            // Authenticate
            send(buildJsonObject {
                put("type", "auth")
                put("user_id", userId)
            }.toString())

            val authResponse = (incoming.receive() as? Frame.Text)?.readText()
            val authMsg = json.parseToJsonElement(authResponse ?: "{}").jsonObject
            if (authMsg["type"]?.jsonPrimitive?.content != "auth_ok") {
                logger.warning("WebSocket auth failed")
                return@webSocket
            }

            logger.info("WebSocket connected and authenticated")
            reconnectDelay = INITIAL_RECONNECT_DELAY

            // Start ping job
            val pingJob = launch {
                while (isActive) {
                    delay(25_000)
                    try {
                        send("""{"type":"ping"}""")
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            // Listen for messages
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleMessage(frame.readText())
                    }
                }
            } finally {
                pingJob.cancel()
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = json.parseToJsonElement(text).jsonObject
            when (msg["type"]?.jsonPrimitive?.content) {
                "new_message" -> {
                    // Fetch pending messages from server
                    scope.launch {
                        try {
                            messageRepository.get().fetchPendingFromServer()
                        } catch (e: Exception) {
                            logger.warning("Failed to fetch after WS signal: ${e.message}")
                        }
                    }
                }
                "pong" -> { /* Expected keepalive response */ }

                "call_offer", "call_answer", "call_ice_candidate",
                "call_hangup", "call_busy", "call_reject" -> {
                    val signal = CallSignalDto(
                        senderId = msg["sender_id"]?.jsonPrimitive?.content ?: return,
                        signalType = msg["type"]?.jsonPrimitive?.content ?: return,
                        callId = msg["call_id"]?.jsonPrimitive?.content ?: "",
                        encryptedPayload = msg["encrypted_payload"]?.jsonPrimitive?.content ?: "",
                    )
                    _incomingCallSignals.tryEmit(signal)
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to parse WebSocket message: ${e.message}")
        }
    }

    companion object {
        private const val INITIAL_RECONNECT_DELAY = 1_000L
        private const val MAX_RECONNECT_DELAY = 60_000L
    }
}

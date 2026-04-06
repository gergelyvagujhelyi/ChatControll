package com.chatcontroll.app.data.remote

import com.chatcontroll.app.BuildConfig
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.data.remote.dto.CallSignalDto
import com.chatcontroll.app.domain.repository.MessageRepository
import android.util.Base64
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
import android.util.Log
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
    private val pqcProvider: PqcProvider,
    private val messageRepository: dagger.Lazy<MessageRepository>,
) {
    private companion object {
        private const val TAG = "WebSocketClient"
        private const val INITIAL_RECONNECT_DELAY = 1_000L
        private const val MAX_RECONNECT_DELAY = 60_000L
    }
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
    @Volatile
    private var reconnectDelay = INITIAL_RECONNECT_DELAY

    @Synchronized
    fun connect() {
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            while (isActive) {
                try {
                    connectWebSocket()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "WebSocket connection failed: ${e.message}")
                }

                // Exponential backoff reconnect
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }
    }

    /**
     * Force an immediate reconnect attempt. Useful when an FCM push arrives
     * while the WebSocket is in an exponential backoff cycle — the server may
     * have buffered call signals that need delivery now.
     */
    @Synchronized
    fun ensureConnected() {
        if (connectionJob?.isActive == true) {
            // Already reconnecting with backoff — restart to connect immediately
            connectionJob?.cancel()
            connectionJob = null
        }
        reconnectDelay = INITIAL_RECONNECT_DELAY
        connect()
    }

    @Synchronized
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        reconnectDelay = INITIAL_RECONNECT_DELAY
    }

    private fun generateAuthToken(): String? {
        val uid = keyManager.getUserId() ?: return null
        val ts = System.currentTimeMillis().toString()
        val payload = "$uid.$ts"
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val signature = keyManager.sign(payloadBytes)
        val sigB64 = Base64.encodeToString(signature, Base64.NO_WRAP)
        val pqcSig = try {
            val mlDsaPrivKey = keyManager.getMlDsaPrivateKey()
            if (mlDsaPrivKey != null) {
                try {
                    "." + Base64.encodeToString(pqcProvider.sign(payloadBytes, mlDsaPrivKey), Base64.NO_WRAP)
                } finally {
                    mlDsaPrivKey.fill(0)
                }
            } else ""
        } catch (_: Exception) { "" }
        return "$payload.$sigB64$pqcSig"
    }

    private suspend fun connectWebSocket() {
        val token = generateAuthToken() ?: return
        val baseUrl = if (BuildConfig.API_BASE_URL.startsWith("https://")) {
            "wss://" + BuildConfig.API_BASE_URL.removePrefix("https://")
        } else {
            "ws://" + BuildConfig.API_BASE_URL.removePrefix("http://")
        }

        client.webSocket("$baseUrl/v1/ws") {
            // Authenticate with signed token
            send(buildJsonObject {
                put("type", "auth")
                put("token", token)
            }.toString())

            val authResponse = (incoming.receive() as? Frame.Text)?.readText()
            val authMsg = json.parseToJsonElement(authResponse ?: "{}").jsonObject
            if (authMsg["type"]?.jsonPrimitive?.content != "auth_ok") {
                if (BuildConfig.DEBUG) Log.w(TAG, "WebSocket auth failed")
                return@webSocket
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket connected and authenticated")
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
                            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to fetch after WS signal: ${e.message}")
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
                        signature = msg["signature"]?.jsonPrimitive?.content ?: "",
                        pqcSignature = msg["pqc_signature"]?.jsonPrimitive?.content ?: "",
                        kemCiphertext = msg["kem_ciphertext"]?.jsonPrimitive?.content ?: "",
                    )
                    _incomingCallSignals.tryEmit(signal)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to parse WebSocket message: ${e.message}")
        }
    }

}

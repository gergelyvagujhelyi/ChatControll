package com.chatcontroll.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.data.remote.dto.CallSignalDto
import com.chatcontroll.app.data.remote.dto.CallSignalRequest
import com.chatcontroll.app.domain.model.CallDirection
import com.chatcontroll.app.domain.model.CallState
import com.chatcontroll.app.domain.model.CallStatus
import com.chatcontroll.app.domain.repository.CryptoEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val cryptoEngine: CryptoEngine,
    private val keyManager: KeyManager,
    private val webSocketClient: WebSocketClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            webSocketClient.incomingCallSignals.collect { signal ->
                handleIncomingSignal(signal)
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    private var webRtcEngine: WebRtcEngine? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _callState = MutableStateFlow<CallState?>(null)
    val callState: StateFlow<CallState?> = _callState.asStateFlow()

    private val pendingIceCandidates = mutableListOf<IceCandidateDto>()

    fun initiateCall(peerId: String, peerDisplayName: String) {
        if (_callState.value != null) return

        val callId = UUID.randomUUID().toString()
        _callState.value = CallState(
            callId = callId,
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            direction = CallDirection.OUTGOING,
            status = CallStatus.RINGING,
        )

        scope.launch {
            try {
                Log.d(TAG, "Setting up WebRTC for outgoing call to $peerId")
                setupWebRtc(peerId)
                Log.d(TAG, "Creating SDP offer")
                val sdp = webRtcEngine!!.createOffer()
                Log.d(TAG, "Sending call_offer signal, SDP length=${sdp.length}")
                sendSignal(peerId, "call_offer", callId, json.encodeToString(SdpPayload(sdp)))
                Log.d(TAG, "call_offer sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate call", e)
                endCall(CallStatus.FAILED)
            }
        }
    }

    fun handleIncomingSignal(signal: CallSignalDto) {
        Log.d(TAG, "Incoming signal: ${signal.signalType} from ${signal.senderId.take(8)}")
        scope.launch {
            try {
                when (signal.signalType) {
                    "call_offer" -> handleOffer(signal)
                    "call_answer" -> handleAnswer(signal)
                    "call_ice_candidate" -> handleIceCandidate(signal)
                    "call_hangup" -> handleRemoteHangup()
                    "call_reject" -> endCall(CallStatus.REJECTED)
                    "call_busy" -> endCall(CallStatus.BUSY)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle signal: ${signal.signalType}", e)
            }
        }
    }

    private suspend fun handleOffer(signal: CallSignalDto) {
        if (_callState.value != null) {
            // Already in a call — send busy
            sendSignal(signal.senderId, "call_busy", signal.callId, "")
            return
        }

        _pendingOfferPayload = signal.encryptedPayload
        val displayName = signal.senderId.take(8)
        _callState.value = CallState(
            callId = signal.callId,
            peerId = signal.senderId,
            peerDisplayName = displayName,
            direction = CallDirection.INCOMING,
            status = CallStatus.RINGING,
        )
    }

    fun acceptCall() {
        val state = _callState.value ?: return
        if (state.direction != CallDirection.INCOMING || state.status != CallStatus.RINGING) return

        _callState.value = state.copy(status = CallStatus.CONNECTING)

        scope.launch {
            try {
                Log.d(TAG, "Accepting call from ${state.peerId.take(8)}")
                setupWebRtc(state.peerId)

                // Decrypt the offer SDP
                val sdpJson = decryptPayload(state.peerId, _pendingOfferPayload ?: return@launch)
                val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
                Log.d(TAG, "Decoded offer SDP, length=${sdpPayload.sdp.length}")

                // Apply pending ICE candidates after setting remote description
                val answerSdp = webRtcEngine!!.handleRemoteOffer(sdpPayload.sdp)
                Log.d(TAG, "Created answer SDP, length=${answerSdp.length}")

                Log.d(TAG, "Applying ${pendingIceCandidates.size} pending ICE candidates")
                for (candidate in pendingIceCandidates) {
                    webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                }
                pendingIceCandidates.clear()

                sendSignal(state.peerId, "call_answer", state.callId, json.encodeToString(SdpPayload(answerSdp)))
                Log.d(TAG, "call_answer sent")

                requestAudioFocus()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept call", e)
                endCall(CallStatus.FAILED)
            }
        }
    }

    fun rejectCall() {
        val state = _callState.value ?: return
        scope.launch {
            sendSignal(state.peerId, "call_reject", state.callId, "")
        }
        endCall(CallStatus.REJECTED)
    }

    fun hangup() {
        val state = _callState.value ?: return
        scope.launch {
            sendSignal(state.peerId, "call_hangup", state.callId, "")
        }
        endCall(CallStatus.ENDED)
    }

    fun toggleMute() {
        _callState.update { state ->
            if (state == null) return
            val newMuted = !state.isMuted
            webRtcEngine?.setMicEnabled(!newMuted)
            state.copy(isMuted = newMuted)
        }
    }

    fun toggleSpeaker() {
        _callState.update { state ->
            if (state == null) return
            val newSpeaker = !state.isSpeakerOn
            audioManager.isSpeakerphoneOn = newSpeaker
            state.copy(isSpeakerOn = newSpeaker)
        }
    }

    private var _pendingOfferPayload: String? = null

    private suspend fun handleAnswer(signal: CallSignalDto) {
        val state = _callState.value ?: return
        _callState.value = state.copy(status = CallStatus.CONNECTING)

        val sdpJson = decryptPayload(signal.senderId, signal.encryptedPayload)
        val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
        webRtcEngine?.handleRemoteAnswer(sdpPayload.sdp)

        // Apply ICE candidates that arrived before the answer
        for (candidate in pendingIceCandidates) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }
        pendingIceCandidates.clear()

        requestAudioFocus()
    }

    private suspend fun handleIceCandidate(signal: CallSignalDto) {
        val candidateJson = decryptPayload(signal.senderId, signal.encryptedPayload)
        val candidate = json.decodeFromString<IceCandidateDto>(candidateJson)

        val status = _callState.value?.status
        if (webRtcEngine != null && status != CallStatus.RINGING && status != CallStatus.CONNECTING) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    private fun handleRemoteHangup() {
        endCall(CallStatus.ENDED)
    }

    private fun endCall(status: CallStatus) {
        _callState.value = _callState.value?.copy(status = status)
        val engine = webRtcEngine
        webRtcEngine = null
        _pendingOfferPayload = null
        pendingIceCandidates.clear()
        abandonAudioFocus()

        // Dispose WebRTC resources off the main thread to avoid ANR
        if (engine != null) {
            scope.launch(Dispatchers.Default) {
                engine.dispose()
            }
        }

        // Clear state after a short delay so UI can show the end status
        scope.launch {
            kotlinx.coroutines.delay(2000)
            if (_callState.value?.status == status) {
                _callState.value = null
            }
        }
    }

    private suspend fun setupWebRtc(peerId: String) {
        val iceServers = try {
            apiService.getIceServers().iceServers.map { dto ->
                val builder = PeerConnection.IceServer.builder(dto.urls)
                if (dto.username != null) builder.setUsername(dto.username)
                if (dto.credential != null) builder.setPassword(dto.credential)
                builder.createIceServer()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch ICE servers, using defaults", e)
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }

        Log.d(TAG, "ICE servers: ${iceServers.map { it.urls }}")
        webRtcEngine = WebRtcEngine(context)
        webRtcEngine?.createPeerConnection(iceServers)

        webRtcEngine?.onIceCandidate = { candidate ->
            Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(60)}")
            scope.launch {
                val dto = IceCandidateDto(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                val state = _callState.value ?: return@launch
                sendSignal(peerId, "call_ice_candidate", state.callId, json.encodeToString(dto))
            }
        }

        webRtcEngine?.onConnectionStateChange = lambda@{ iceState ->
            when (iceState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    _callState.update { state ->
                        state?.copy(
                            status = CallStatus.CONNECTED,
                            connectedAt = System.currentTimeMillis(),
                        )
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    endCall(CallStatus.FAILED)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // May reconnect, don't end immediately
                }
                else -> {}
            }
        }
    }

    private suspend fun sendSignal(peerId: String, signalType: String, callId: String, payload: String) {
        val encrypted = if (payload.isNotEmpty()) encryptPayload(peerId, payload) else ""
        try {
            apiService.sendCallSignal(
                CallSignalRequest(
                    recipientId = peerId,
                    signalType = signalType,
                    callId = callId,
                    encryptedPayload = encrypted,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send signal $signalType", e)
        }
    }

    private suspend fun encryptPayload(peerId: String, plaintext: String): String {
        val sessionKeys = keyManager.getCachedSessionKeys(peerId)
            ?: throw IllegalStateException("No session keys for $peerId — cannot encrypt call signal")
        val envelope = cryptoEngine.encrypt(sessionKeys, plaintext.toByteArray(Charsets.UTF_8))
        // Pack as: nonce_b64.ciphertext_b64
        val nonceB64 = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP)
        return "$nonceB64.$ctB64"
    }

    private suspend fun decryptPayload(peerId: String, encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        val sessionKeys = keyManager.getCachedSessionKeys(peerId)
            ?: throw IllegalStateException("No session keys for $peerId — cannot decrypt call signal")
        require(encrypted.contains('.')) { "Invalid encrypted payload format" }
        val parts = encrypted.split('.', limit = 2)
        val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val envelope = com.chatcontroll.app.domain.repository.EncryptedEnvelope(ciphertext, nonce)
        val plainBytes = cryptoEngine.decrypt(sessionKeys, envelope)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun requestAudioFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    companion object {
        private const val TAG = "CallManager"
    }
}

@Serializable
private data class SdpPayload(val sdp: String)

@Serializable
data class IceCandidateDto(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String,
)

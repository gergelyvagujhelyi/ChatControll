package com.chatcontroll.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import com.chatcontroll.app.BuildConfig
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.data.remote.dto.CallSignalDto
import com.chatcontroll.app.data.remote.dto.CallSignalRequest
import com.chatcontroll.app.domain.model.CallDirection
import com.chatcontroll.app.domain.model.CallState
import com.chatcontroll.app.domain.model.CallStatus
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    private val keyManager: KeyManager,
    private val cryptoEngine: CryptoEngine,
    private val webSocketClient: WebSocketClient,
    private val contactDao: com.chatcontroll.app.data.local.dao.ContactDao,
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
    private var remoteDescriptionSet = false

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
                logDebug("Setting up WebRTC for outgoing call")
                setupWebRtc(peerId)
                logDebug("Creating SDP offer")
                val sdp = webRtcEngine!!.createOffer()
                logDebug("Sending call_offer signal")
                sendSignal(peerId, "call_offer", callId, json.encodeToString(SdpPayload(sdp)))
                logDebug("call_offer sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate call", e)
                endCall(CallStatus.FAILED)
            }
        }
    }

    fun handleIncomingSignal(signal: CallSignalDto) {
        logDebug("Incoming signal: ${signal.signalType}")
        scope.launch {
            try {
                // Verify signature if present
                if (signal.signature.isNotEmpty()) {
                    val localUserId = keyManager.getUserId() ?: return@launch
                    val contact = contactDao.getByUserId(signal.senderId)
                    if (contact != null) {
                        val sigPayload = signal.senderId.toByteArray(Charsets.UTF_8) +
                            localUserId.toByteArray(Charsets.UTF_8) +
                            signal.signalType.toByteArray(Charsets.UTF_8) +
                            signal.callId.toByteArray(Charsets.UTF_8) +
                            signal.encryptedPayload.toByteArray(Charsets.UTF_8)
                        val sig = Base64.decode(signal.signature, Base64.NO_WRAP)
                        val valid = cryptoEngine.verify(sigPayload, sig, contact.publicSigningKey)
                        if (!valid) {
                            Log.w(TAG, "Call signal signature verification failed")
                            return@launch
                        }
                    }
                }
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
                logDebug("Accepting incoming call")
                setupWebRtc(state.peerId)

                // Decrypt the offer SDP
                val sdpJson = decryptPayload(state.peerId, _pendingOfferPayload ?: return@launch)
                val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
                logDebug("Decoded offer SDP")

                // Apply pending ICE candidates after setting remote description
                val answerSdp = webRtcEngine!!.handleRemoteOffer(sdpPayload.sdp)
                logDebug("Created answer SDP")

                remoteDescriptionSet = true
                logDebug("Applying ${pendingIceCandidates.size} pending ICE candidates")
                for (candidate in pendingIceCandidates) {
                    webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                }
                pendingIceCandidates.clear()

                sendSignal(state.peerId, "call_answer", state.callId, json.encodeToString(SdpPayload(answerSdp)))
                logDebug("call_answer sent")

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
        val state = _callState.value ?: return
        val newMuted = !state.isMuted
        webRtcEngine?.setMicEnabled(!newMuted)
        _callState.update { it?.copy(isMuted = newMuted) }
    }

    fun toggleSpeaker() {
        val state = _callState.value ?: return
        val newSpeaker = !state.isSpeakerOn
        audioManager.isSpeakerphoneOn = newSpeaker
        _callState.update { it?.copy(isSpeakerOn = newSpeaker) }
    }

    private var _pendingOfferPayload: String? = null

    private suspend fun handleAnswer(signal: CallSignalDto) {
        val state = _callState.value ?: return
        _callState.value = state.copy(status = CallStatus.CONNECTING)

        val sdpJson = decryptPayload(signal.senderId, signal.encryptedPayload)
        val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
        webRtcEngine?.handleRemoteAnswer(sdpPayload.sdp)

        remoteDescriptionSet = true
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

        if (webRtcEngine != null && remoteDescriptionSet) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        } else if (pendingIceCandidates.size < MAX_PENDING_ICE_CANDIDATES) {
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
        remoteDescriptionSet = false
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

        logDebug("ICE servers configured: ${iceServers.size}")
        webRtcEngine = WebRtcEngine(context)
        webRtcEngine?.createPeerConnection(iceServers)

        webRtcEngine?.onIceCandidate = { candidate ->
            logDebug("Local ICE candidate generated")
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
        val senderId = keyManager.getUserId() ?: return
        val sigPayload = senderId.toByteArray(Charsets.UTF_8) +
            peerId.toByteArray(Charsets.UTF_8) +
            signalType.toByteArray(Charsets.UTF_8) +
            callId.toByteArray(Charsets.UTF_8) +
            encrypted.toByteArray(Charsets.UTF_8)
        val signature = Base64.encodeToString(keyManager.sign(sigPayload), Base64.NO_WRAP)
        try {
            apiService.sendCallSignal(
                CallSignalRequest(
                    recipientId = peerId,
                    signalType = signalType,
                    callId = callId,
                    encryptedPayload = encrypted,
                    signature = signature,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send signal $signalType", e)
        }
    }

    /**
     * Ensure session keys exist for [peerId], establishing a new session if
     * the in-memory cache is empty (e.g. after app restart).
     */
    private suspend fun ensureSessionKeys(peerId: String): SessionKeys {
        keyManager.getCachedSessionKeys(peerId)?.let { return it }

        logDebug("No cached session keys, establishing session")
        val bundle = apiService.fetchKeyBundle(peerId)
            ?: throw IllegalStateException("Cannot fetch key bundle for $peerId")
        val localKeyPair = keyManager.loadIdentityKeyPair()
            ?: throw IllegalStateException("No local identity key pair")

        val pubIdKey = Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)
        val pubSignKey = Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP)

        val sessionKeys = cryptoEngine.establishSession(
            localIdentity = localKeyPair,
            remotePublicBundle = PublicKeyBundle(
                publicSigningKey = pubSignKey,
                publicIdentityKey = pubIdKey,
                pqcEncapsulationKey = ByteArray(0),
            ),
        )
        keyManager.cacheSessionKeys(peerId, sessionKeys)
        return sessionKeys
    }

    /**
     * Encrypt call signals using AES-256-GCM with the static session sendKey.
     * This intentionally bypasses the Double Ratchet to avoid advancing the
     * message chain — call signals are ephemeral and may be lost/reordered.
     */
    private suspend fun encryptPayload(peerId: String, plaintext: String): String {
        val sessionKeys = ensureSessionKeys(peerId)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKeys.sendKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$nonceB64.$ctB64"
    }

    private suspend fun decryptPayload(peerId: String, encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        val sessionKeys = ensureSessionKeys(peerId)
        require(encrypted.contains('.')) { "Invalid encrypted payload format" }
        val parts = encrypted.split('.', limit = 2)
        val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKeys.receiveKey, "AES"), GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
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

    private fun logDebug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    companion object {
        private const val TAG = "CallManager"
        private const val MAX_PENDING_ICE_CANDIDATES = 100
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

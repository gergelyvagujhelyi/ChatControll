package com.chatcontroll.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import com.chatcontroll.app.BuildConfig
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.hkdfSha256
import com.chatcontroll.app.crypto.lengthPrefixed
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.data.remote.dto.CallSignalDto
import com.chatcontroll.app.data.remote.dto.CallSignalRequest
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.data.local.entity.ConversationEntity
import com.chatcontroll.app.data.local.entity.MessageEntity
import com.chatcontroll.app.domain.model.CallDirection
import com.chatcontroll.app.domain.model.CallState
import com.chatcontroll.app.domain.model.CallStatus
import com.chatcontroll.app.domain.model.MessageState
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.SessionKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    private val classicalKeyAgreement: com.chatcontroll.app.crypto.ClassicalKeyAgreement,
    private val webSocketClient: WebSocketClient,
    private val contactDao: com.chatcontroll.app.data.local.dao.ContactDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
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

    private val signalMutex = kotlinx.coroutines.sync.Mutex()
    private var ringingTimeoutJob: Job? = null
    private val pendingIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidateDto>())
    @Volatile
    private var remoteDescriptionSet = false
    /** Per-call cache of derived session keys — cleared in endCall(). */
    private val _callSessionKeys = ConcurrentHashMap<String, SessionKeys>()
    /** Track seen signal signatures with timestamps to reject replays.
     *  Entries survive endCall() and are evicted after [SIGNATURE_TTL_MS].
     *  Insertion order (accessOrder=false) so time-based eviction is correct. */
    private val seenSignalSignatures = LinkedHashMap<String, Long>(64, 0.75f, false)
    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    fun clearCallError() { _callError.value = null }

    fun initiateCall(peerId: String, peerDisplayName: String) {
        if (_callState.value != null) {
            _callError.value = "Already in a call"
            return
        }

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
                val sdp = webRtcEngine?.createOffer()
                    ?: throw IllegalStateException("WebRTC engine not initialized")
                logDebug("Sending call_offer signal")
                val result = sendSignal(peerId, "call_offer", callId, json.encodeToString(SdpPayload(sdp)))
                if (result == null) {
                    logWarn("call_offer failed to send — network error")
                    endCall(CallStatus.FAILED)
                    return@launch
                }
                if (result) {
                    logDebug("call_offer delivered via WebSocket")
                } else {
                    logDebug("call_offer buffered by server — waiting for FCM to wake recipient")
                }
                // Start ringing timeout — if no answer within the limit, give up
                startRingingTimeout(callId)
            } catch (e: Exception) {
                logError("Failed to initiate call", e)
                endCall(CallStatus.FAILED)
            }
        }
    }

    fun handleIncomingSignal(signal: CallSignalDto) {
        logDebug("Incoming signal: ${signal.signalType}")
        scope.launch {
            // --- Contact resolution + signature verification outside the mutex
            // so network requests don't block ICE candidate / hangup processing.
            val verifiedContact: com.chatcontroll.app.data.local.entity.ContactEntity
            var needsPersist = false
            try {
                val localUserId = keyManager.getUserId() ?: return@launch
                if (signal.signature.isEmpty()) {
                    logWarn("Rejecting unsigned call signal from ${signal.senderId.take(8)}")
                    return@launch
                }
                var contact = contactDao.getByUserId(signal.senderId)
                if (contact == null) {
                    // Check if we already fetched this contact for a pending call
                    // — avoids redundant network fetches for ICE candidates.
                    contact = _pendingNewContact?.takeIf { it.userId == signal.senderId }
                    if (contact == null) {
                        // Unknown sender — fetch their key bundle from the server
                        // for signature verification. Do NOT persist yet — only
                        // save after the signature is verified to prevent an
                        // attacker from polluting the contacts table.
                        logDebug("Contact not found locally for ${signal.senderId.take(8)}, fetching from server")
                        contact = fetchContactBundle(signal.senderId)
                        if (contact == null) {
                            logWarn("Rejecting call signal from unknown contact ${signal.senderId.take(8)} — server lookup failed")
                            return@launch
                        }
                        logDebug("Fetched key bundle for ${signal.senderId.take(8)}")
                    }
                    needsPersist = true
                }
                val sigPayload = lengthPrefixed(signal.senderId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(localUserId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(signal.signalType.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(signal.callId.toByteArray(Charsets.UTF_8)) +
                    signal.encryptedPayload.toByteArray(Charsets.UTF_8)
                val sig = Base64.decode(signal.signature, Base64.NO_WRAP)
                val valid = cryptoEngine.verify(sigPayload, sig, contact.publicSigningKey)
                if (!valid) {
                    logWarn("Call signal signature verification failed for ${signal.senderId.take(8)}")
                    return@launch
                }
                logDebug("Signature verified for ${signal.signalType} from ${signal.senderId.take(8)}")
                verifiedContact = contact
            } catch (e: Exception) {
                logError("Failed to verify signal: ${signal.signalType}", e)
                return@launch
            }

            // --- Replay check + lightweight validation under the mutex (no I/O).
            // All _pendingNewContact reads/writes are inside this lock so the
            // pattern is safe regardless of dispatcher.
            signalMutex.withLock {
                // Update _pendingNewContact under the lock before dispatch
                if (needsPersist && _pendingNewContact?.userId != signal.senderId) {
                    _pendingNewContact = verifiedContact
                    logDebug("Deferring contact persistence for ${signal.senderId.take(8)} until call accepted")
                }

                // Reject replayed signals (same signature = same signal)
                val now = System.currentTimeMillis()
                // Evict expired entries via iterator to avoid ConcurrentModificationException.
                val iter = seenSignalSignatures.entries.iterator()
                while (iter.hasNext()) {
                    if (now - iter.next().value > SIGNATURE_TTL_MS) iter.remove()
                }
                if (seenSignalSignatures.containsKey(signal.signature)) {
                    logDebug("Rejecting replayed call signal")
                    return@launch
                }
                seenSignalSignatures[signal.signature] = now
                while (seenSignalSignatures.size > MAX_SEEN_SIGNATURES) {
                    seenSignalSignatures.remove(seenSignalSignatures.keys.first())
                }
                when (signal.signalType) {
                    "call_offer" -> {
                        if (_callState.value != null) {
                            // Already in a call — flag for busy signal outside mutex
                        } else {
                            val displayName = verifiedContact.displayName
                            val isNew = _pendingNewContact != null
                            _callState.value = CallState(
                                callId = signal.callId,
                                peerId = signal.senderId,
                                peerDisplayName = displayName,
                                direction = CallDirection.INCOMING,
                                status = CallStatus.RINGING,
                                isNewContact = isNew,
                            )
                            _pendingOfferPayload = signal.encryptedPayload
                        }
                    }
                    "call_answer" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            logWarn("Ignoring answer: no matching active call")
                            return@launch
                        }
                        _callState.value = state.copy(status = CallStatus.CONNECTING)
                    }
                    "call_ice_candidate" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            logDebug("Ignoring ICE candidate: no matching active call")
                            return@launch
                        }
                    }
                    "call_hangup", "call_reject", "call_busy" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            logDebug("Ignoring ${signal.signalType}: no matching active call")
                            return@launch
                        }
                        when (signal.signalType) {
                            "call_hangup" -> endCall(CallStatus.ENDED)
                            "call_reject" -> endCall(CallStatus.REJECTED)
                            "call_busy" -> endCall(CallStatus.BUSY)
                        }
                        return@launch
                    }
                }
            } // signalMutex

            // --- Heavy processing outside the mutex (network I/O, decrypt, WebRTC)
            try {
                when (signal.signalType) {
                    "call_offer" -> {
                        if (_callState.value?.callId == signal.callId) {
                            // Offer accepted — start callee ringing timeout
                            startCalleeRingingTimeout(signal.callId)
                        } else {
                            // Already in a call — send busy outside the mutex
                            sendSignal(signal.senderId, "call_busy", signal.callId, "")
                        }
                    }
                    "call_answer" -> processAnswerPayload(signal)
                    "call_ice_candidate" -> processIceCandidatePayload(signal)
                }
            } catch (e: Exception) {
                logError("Failed to process ${signal.signalType} payload", e)
                if (signal.signalType == "call_answer") {
                    endCall(CallStatus.FAILED)
                }
            }
        }
    }

    fun acceptCall() {
        val state = _callState.value ?: return
        if (state.direction != CallDirection.INCOMING || state.status != CallStatus.RINGING) return

        _callState.value = state.copy(status = CallStatus.CONNECTING, isNewContact = false)

        scope.launch {
            // Persist the new contact now that the user has approved the call.
            // Access under signalMutex to synchronize with handleIncomingSignal().
            signalMutex.withLock {
                _pendingNewContact?.let { contact ->
                    contactDao.upsert(contact)
                    logDebug("Persisted new contact ${contact.userId.take(8)} on call accept")
                    _pendingNewContact = null
                }
            }

            try {
                val offerPayload = _pendingOfferPayload
                if (offerPayload == null) {
                    logError("No pending offer payload — cannot accept call", null)
                    endCall(CallStatus.FAILED)
                    return@launch
                }

                logDebug("Setting up WebRTC...")
                setupWebRtc(state.peerId)
                logDebug("WebRTC setup complete")

                val sdpJson = decryptPayload(state.peerId, state.callId, offerPayload)
                val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
                logDebug("Decoded offer SDP (${sdpPayload.sdp.length} chars)")

                val answerSdp = webRtcEngine?.handleRemoteOffer(sdpPayload.sdp)
                    ?: throw IllegalStateException("WebRTC engine not initialized")
                logDebug("Created answer SDP")

                remoteDescriptionSet = true
                drainPendingIceCandidates()

                sendSignal(state.peerId, "call_answer", state.callId, json.encodeToString(SdpPayload(answerSdp)))
                logDebug("call_answer sent")

                requestAudioFocus()
            } catch (e: Exception) {
                logError("Failed to accept call: ${e::class.simpleName}: ${e.message}", e)
                endCall(CallStatus.FAILED)
                try {
                    sendSignal(state.peerId, "call_hangup", state.callId, "")
                } catch (_: Exception) { }
            }
        }
    }

    fun rejectCall() {
        val state = _callState.value ?: return
        endCall(CallStatus.REJECTED)
        scope.launch {
            val result = sendSignal(state.peerId, "call_reject", state.callId, "")
            if (result != true) logWarn("Reject signal not delivered — peer may not know call was declined")
        }
    }

    fun hangup() {
        val state = _callState.value ?: return
        endCall(CallStatus.ENDED)
        scope.launch {
            val result = sendSignal(state.peerId, "call_hangup", state.callId, "")
            if (result != true) logWarn("Hangup signal not delivered — peer may not know call ended")
        }
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
        setSpeakerphone(newSpeaker)
        _callState.update { it?.copy(isSpeakerOn = newSpeaker) }
    }

    private fun setSpeakerphone(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (on) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    @Volatile
    private var _pendingOfferPayload: String? = null
    /** Contact fetched from the server for an unknown caller — persisted only on accept. */
    @Volatile
    private var _pendingNewContact: com.chatcontroll.app.data.local.entity.ContactEntity? = null

    private fun startRingingTimeout(callId: String) {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(RINGING_TIMEOUT_MS)
            val state = _callState.value
            if (state != null && state.callId == callId && state.status == CallStatus.RINGING) {
                logWarn("Ringing timeout — no answer after ${RINGING_TIMEOUT_MS / 1000}s")
                sendSignal(state.peerId, "call_hangup", callId, "")
                endCall(CallStatus.UNAVAILABLE)
            }
        }
    }

    private fun startCalleeRingingTimeout(callId: String) {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(CALLEE_RINGING_TIMEOUT_MS)
            val state = _callState.value
            if (state != null && state.callId == callId && state.status == CallStatus.RINGING
                && state.direction == CallDirection.INCOMING
            ) {
                logWarn("Callee ringing timeout — not answered after ${CALLEE_RINGING_TIMEOUT_MS / 1000}s")
                endCall(CallStatus.ENDED)
            }
        }
    }

    /** Process answer payload — called outside signalMutex after validation. */
    private suspend fun processAnswerPayload(signal: CallSignalDto) {
        val sdpJson = decryptPayload(signal.senderId, signal.callId, signal.encryptedPayload)
        val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
        webRtcEngine?.handleRemoteAnswer(sdpPayload.sdp)

        remoteDescriptionSet = true
        drainPendingIceCandidates()

        requestAudioFocus()
    }

    /** Atomically snapshot + clear pendingIceCandidates, then apply to WebRTC. */
    private fun drainPendingIceCandidates() {
        val snapshot: List<IceCandidateDto>
        synchronized(pendingIceCandidates) {
            snapshot = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
        }
        logDebug("Applying ${snapshot.size} pending ICE candidates")
        for (candidate in snapshot) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }
    }

    /** Process ICE candidate payload — called outside signalMutex after validation. */
    private suspend fun processIceCandidatePayload(signal: CallSignalDto) {
        val candidateJson = decryptPayload(signal.senderId, signal.callId, signal.encryptedPayload)
        val candidate = json.decodeFromString<IceCandidateDto>(candidateJson)

        if (webRtcEngine != null && remoteDescriptionSet) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        } else if (pendingIceCandidates.size < MAX_PENDING_ICE_CANDIDATES) {
            pendingIceCandidates.add(candidate)
        } else {
            logWarn("Dropping ICE candidate: pending queue full ($MAX_PENDING_ICE_CANDIDATES)")
        }
    }

    private fun endCall(status: CallStatus) {
        val endingState = _callState.value
        _callState.value = endingState?.copy(status = status)
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        val engine = webRtcEngine
        webRtcEngine = null
        _pendingOfferPayload = null
        // Clear _pendingNewContact under signalMutex to stay consistent with
        // acceptCall() and handleIncomingSignal(). Launch a coroutine so we
        // can properly acquire the suspending Mutex instead of using tryLock,
        // which would race with signal handlers reading the field under lock.
        scope.launch {
            signalMutex.withLock {
                _pendingNewContact = null
            }
        }
        pendingIceCandidates.clear()
        remoteDescriptionSet = false
        _callSessionKeys.values.forEach { keys ->
            keys.sendKey.fill(0)
            keys.receiveKey.fill(0)
        }
        _callSessionKeys.clear()
        abandonAudioFocus()

        // Dispose WebRTC resources off the main thread to avoid ANR
        if (engine != null) {
            scope.launch(Dispatchers.Default) {
                engine.dispose()
            }
        }

        // Record the call event in conversation history
        if (endingState != null) {
            scope.launch {
                recordCallEvent(endingState, status)
            }
        }

        // Clear state after a short delay so UI can show the end status.
        // Compare callId (not status) so a rapid back-to-back call isn't cleared.
        val endedCallId = endingState?.callId
        scope.launch {
            kotlinx.coroutines.delay(2000)
            if (_callState.value?.callId == endedCallId) {
                _callState.value = null
            }
        }
    }

    /**
     * Insert a call event message into the conversation so calls appear
     * in the chat history alongside text messages.
     */
    private suspend fun recordCallEvent(callState: CallState, endStatus: CallStatus) {
        try {
            val localUserId = keyManager.getUserId() ?: return
            val peerId = callState.peerId

            // Don't create orphaned conversations for contacts not in the DB
            // (e.g., declined/missed calls from unknown callers)
            if (contactDao.getByUserId(peerId) == null) {
                logDebug("Skipping call event recording — contact ${peerId.take(8)} not persisted")
                return
            }

            val isOutgoing = callState.direction == CallDirection.OUTGOING
            val wasConnected = callState.connectedAt != null

            // Determine the call duration (0 if never connected)
            val durationSeconds = if (wasConnected) {
                ((System.currentTimeMillis() - callState.connectedAt!!) / 1000).coerceAtLeast(0)
            } else {
                0L
            }

            // Map direction + end status to a message state.
            // Use wasConnected (not durationSeconds) to avoid sub-second
            // connected calls being classified as missed.
            val messageState = when {
                isOutgoing && wasConnected -> MessageState.CALL_OUTGOING
                isOutgoing -> MessageState.CALL_OUTGOING_MISSED
                wasConnected -> MessageState.CALL_INCOMING
                else -> MessageState.CALL_MISSED
            }

            // Find or create conversation for this peer
            val conversationId = getOrCreateConversationId(peerId)

            val now = System.currentTimeMillis()
            val messageId = "call-${callState.callId}"

            messageDao.insert(
                MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    senderId = if (isOutgoing) localUserId else peerId,
                    recipientId = if (isOutgoing) peerId else localUserId,
                    encryptedBody = ByteArray(0),
                    nonce = ByteArray(0),
                    plaintext = durationSeconds.toString(),
                    state = messageState.name,
                    timestamp = now,
                    expiresAt = null,
                    isOutgoing = isOutgoing,
                )
            )

            // Update the conversation preview
            val preview = when (messageState) {
                MessageState.CALL_OUTGOING -> "Voice call"
                MessageState.CALL_OUTGOING_MISSED -> when (endStatus) {
                    CallStatus.REJECTED -> "Call declined"
                    CallStatus.BUSY -> "Busy"
                    CallStatus.UNAVAILABLE -> "No answer"
                    else -> "Outgoing call"
                }
                MessageState.CALL_INCOMING -> "Voice call"
                MessageState.CALL_MISSED -> when (endStatus) {
                    CallStatus.REJECTED -> "Declined call"
                    else -> "Missed call"
                }
                else -> "Call"
            }
            updateConversationPreview(conversationId, peerId, preview, now)

            logDebug("Recorded call event: $messageState, duration=${durationSeconds}s")
        } catch (e: Exception) {
            logError("Failed to record call event", e)
        }
    }

    private suspend fun getOrCreateConversationId(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id

        val contact = contactDao.getByUserId(contactId)
        val displayName = contact?.displayName ?: contactId.take(8)

        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                contactDisplayName = displayName,
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = true,
            )
        )
        return id
    }

    private suspend fun updateConversationPreview(
        conversationId: String,
        contactId: String,
        preview: String,
        timestamp: Long,
    ) {
        val existing = conversationDao.getByContactId(contactId)
        conversationDao.upsert(
            ConversationEntity(
                id = conversationId,
                contactId = contactId,
                contactDisplayName = existing?.contactDisplayName ?: contactId.take(8),
                lastMessagePreview = preview,
                lastMessageTimestamp = timestamp,
                unreadCount = existing?.unreadCount ?: 0,
                isEncrypted = true,
                isApproved = existing?.isApproved ?: true,
                needsSessionReset = existing?.needsSessionReset ?: false,
            )
        )
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
            logWarn("Failed to fetch ICE servers, using defaults", e)
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }

        val hasTurn = iceServers.any { server ->
            server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
        }
        if (!hasTurn) {
            logWarn("No TURN relay available — call may fail behind symmetric NAT")
            _callState.update { it?.copy(relayUnavailable = true) }
        }

        logDebug("ICE servers configured: ${iceServers.size} (TURN: $hasTurn)")
        webRtcEngine = WebRtcEngine(context)
        webRtcEngine?.createPeerConnection(iceServers)

        // Enable frame-level E2E encryption on media tracks
        try {
            val sessionKeys = ensureSessionKeys(peerId)
            val callId = _callState.value?.callId
                ?: throw IllegalStateException("No active call")
            val mediaKey = deriveMediaKey(sessionKeys, callId)
            webRtcEngine?.enableFrameEncryption(mediaKey)
            mediaKey.fill(0)
            logDebug("Frame encryption enabled for call $callId")
        } catch (e: Exception) {
            logError("Failed to enable frame encryption", e)
            throw e // Fail the call — never allow unencrypted media
        }

        webRtcEngine?.onFrameCryptionStateChange = { state ->
            logDebug("Frame cryption state: $state")
        }

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
                    val isRelayIssue = _callState.value?.relayUnavailable == true
                    endCall(if (isRelayIssue) CallStatus.NO_RELAY else CallStatus.FAILED)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // May reconnect, don't end immediately
                }
                else -> {}
            }
        }
    }

    /**
     * Send a call signal to the peer. Retries on network errors only — if the
     * server accepted the signal but the recipient's WebSocket is down, the
     * server has already buffered the signal and sent an FCM push, so
     * client-side retries would be redundant.
     *
     * Returns true if the signal was delivered immediately via WebSocket,
     * false if accepted-but-not-delivered, null if all attempts failed.
     */
    private suspend fun sendSignal(peerId: String, signalType: String, callId: String, payload: String): Boolean? {
        val encrypted = if (payload.isNotEmpty()) encryptPayload(peerId, callId, payload) else ""
        val senderId = keyManager.getUserId() ?: return null
        val sigPayload = lengthPrefixed(senderId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(peerId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(signalType.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(callId.toByteArray(Charsets.UTF_8)) +
            encrypted.toByteArray(Charsets.UTF_8)
        val signature = Base64.encodeToString(keyManager.sign(sigPayload), Base64.NO_WRAP)
        val request = CallSignalRequest(
            recipientId = peerId,
            signalType = signalType,
            callId = callId,
            encryptedPayload = encrypted,
            signature = signature,
        )
        repeat(SIGNAL_SEND_RETRIES) { attempt ->
            try {
                val response = apiService.sendCallSignal(request)
                if (response.delivered) return true
                // Server accepted and buffered the signal + sent FCM push.
                // No need to retry — return false to let the caller decide.
                logWarn("Signal $signalType accepted but not delivered (server buffered)")
                return false
            } catch (e: Exception) {
                logError("Failed to send signal $signalType (attempt ${attempt + 1}/$SIGNAL_SEND_RETRIES)", e)
                if (attempt < SIGNAL_SEND_RETRIES - 1) {
                    kotlinx.coroutines.delay(500L * (attempt + 1))
                }
            }
        }
        return null
    }

    /**
     * Fetch a user's key bundle from the server WITHOUT persisting it.
     * Returns an in-memory ContactEntity for signature verification.
     * Only persist via [contactDao.upsert] after the signature is verified.
     */
    private suspend fun fetchContactBundle(userId: String): com.chatcontroll.app.data.local.entity.ContactEntity? {
        return try {
            val bundle = apiService.fetchKeyBundle(userId) ?: return null
            val pubIdKey = Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)
            val pubSignKey = Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP)
            com.chatcontroll.app.data.local.entity.ContactEntity(
                userId = userId,
                displayName = userId.take(8),
                publicIdentityKey = pubIdKey,
                publicSigningKey = pubSignKey,
            )
        } catch (e: Exception) {
            logError("Failed to fetch key bundle for ${userId.take(8)}", e)
            null
        }
    }

    /**
     * Derive call session keys for [peerId] from current identity keys.
     *
     * Call keys are derived independently from the messaging Double Ratchet
     * to avoid corrupting ratchet state. Both sides compute the same keys
     * because:
     * 1. X25519 shared secret is symmetric (A·B == B·A)
     * 2. HKDF is deterministic
     * 3. Role assignment uses lexicographic comparison of public keys
     *
     * Keys are cached per-call to avoid redundant API calls for ICE
     * candidates. The cache is cleared in [endCall].
     */
    private suspend fun ensureSessionKeys(peerId: String): SessionKeys {
        // Fast path: already derived for this peer during this call
        _callSessionKeys[peerId]?.let { return it }

        val bundle = apiService.fetchKeyBundle(peerId)
            ?: throw IllegalStateException("Cannot fetch key bundle for $peerId")
        val remotePubIdKey = Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)

        val localKeyPair = keyManager.loadIdentityKeyPair()
            ?: throw IllegalStateException("No local identity key pair")

        // X25519 shared secret
        val classicalSecret = classicalKeyAgreement.agree(
            privateKey = localKeyPair.privateIdentityKey,
            remotePublicKey = remotePubIdKey,
        )

        // Derive base shared secret (same HKDF as RatchetSessionManager, no PQC)
        val sharedSecret = hkdfSha256(
            ikm = classicalSecret,
            salt = "ChatControll-v1-ratchet-init".toByteArray(Charsets.UTF_8),
            info = "hybrid-key-establishment".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        classicalSecret.fill(0)

        // Derive bidirectional chain keys
        val chainMaterial = hkdfSha256(
            ikm = sharedSecret,
            salt = "ChatControll-v1-chains".toByteArray(Charsets.UTF_8),
            info = "bidirectional-chains".toByteArray(Charsets.UTF_8),
            length = 64,
        )
        sharedSecret.fill(0)

        val chainA = chainMaterial.copyOfRange(0, 32)
        val chainB = chainMaterial.copyOfRange(32, 64)
        chainMaterial.fill(0)

        // Deterministic role: same comparison as RatchetSessionManager
        val isInitiator = localKeyPair.publicIdentityKey.toCallHex() < remotePubIdKey.toCallHex()

        val sessionKeys = SessionKeys(
            sendKey = if (isInitiator) chainA else chainB,
            receiveKey = if (isInitiator) chainB else chainA,
            sessionId = "call-$peerId",
            pqcEstablished = false,
        )

        _callSessionKeys[peerId] = sessionKeys
        logDebug("Derived call session keys for ${peerId.take(8)} (initiator=$isInitiator)")
        return sessionKeys
    }

    /**
     * Derive a per-call ephemeral key from the session key and callId.
     * This provides call-level forward secrecy: compromise of one call's
     * key does not expose signals from other calls.
     */
    private fun deriveCallKey(sessionKey: ByteArray, callId: String): ByteArray {
        return hkdfSha256(
            ikm = sessionKey,
            salt = callId.toByteArray(Charsets.UTF_8),
            info = "ChatControll-call-signal".toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    /**
     * Derive a symmetric media-encryption key that is identical on both peers.
     * XOR of sendKey and receiveKey is commutative (Alice's sendKey == Bob's
     * receiveKey), so both sides compute the same value.
     */
    private fun deriveMediaKey(sessionKeys: SessionKeys, callId: String): ByteArray {
        val size = minOf(sessionKeys.sendKey.size, sessionKeys.receiveKey.size)
        require(size > 0) { "Session keys are empty — cannot derive media key" }
        val xored = ByteArray(size) { i ->
            (sessionKeys.sendKey[i].toInt() xor sessionKeys.receiveKey[i].toInt()).toByte()
        }
        val key = hkdfSha256(
            ikm = xored,
            salt = callId.toByteArray(Charsets.UTF_8),
            info = "ChatControll-call-media".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        xored.fill(0)
        return key
    }

    /**
     * Encrypt call signals using AES-256-GCM with a per-call ephemeral key
     * derived from the session key and callId. This bypasses the Double Ratchet
     * to avoid advancing the message chain — call signals are ephemeral and
     * may be lost/reordered.
     */
    private suspend fun encryptPayload(peerId: String, callId: String, plaintext: String): String {
        val sessionKeys = ensureSessionKeys(peerId)
        val callKey = deriveCallKey(sessionKeys.sendKey, callId)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(callKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        callKey.fill(0)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$nonceB64.$ctB64"
    }

    private suspend fun decryptPayload(peerId: String, callId: String, encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        val sessionKeys = ensureSessionKeys(peerId)
        val callKey = deriveCallKey(sessionKeys.receiveKey, callId)
        require(encrypted.contains('.')) { "Invalid encrypted payload format" }
        val parts = encrypted.split('.', limit = 2)
        val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(callKey, "AES"), GCMParameterSpec(128, nonce))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } finally {
            callKey.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
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
        setSpeakerphone(false)
    }

    private fun logDebug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    private fun logWarn(msg: String, e: Throwable? = null) {
        Log.w(TAG, msg, e)
    }

    private fun logError(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
    }

    companion object {
        private const val TAG = "CallManager"
        private const val MAX_PENDING_ICE_CANDIDATES = 100
        private const val SIGNATURE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_SEEN_SIGNATURES = 500
        private const val SIGNAL_SEND_RETRIES = 3
        /** How long to wait in RINGING before giving up (ms) — caller side. */
        private const val RINGING_TIMEOUT_MS = 35_000L
        /** Callee ringing timeout — longer than caller's so caller hangup arrives first. */
        private const val CALLEE_RINGING_TIMEOUT_MS = 45_000L
    }
}

private fun ByteArray.toCallHex(): String = joinToString("") { "%02x".format(it) }

@Serializable
private data class SdpPayload(val sdp: String)

@Serializable
data class IceCandidateDto(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String,
)

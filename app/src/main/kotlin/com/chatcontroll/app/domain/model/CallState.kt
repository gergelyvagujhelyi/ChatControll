package com.chatcontroll.app.domain.model

enum class CallDirection { OUTGOING, INCOMING }

enum class CallStatus {
    IDLE,
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    FAILED,
    /** ICE connection failed because no TURN relay was available. */
    NO_RELAY,
    REJECTED,
    BUSY,
    /** Recipient could not be reached (offline / signal not delivered). */
    UNAVAILABLE,
}

data class CallState(
    val callId: String,
    val peerId: String,
    val peerDisplayName: String,
    val direction: CallDirection,
    val status: CallStatus,
    val startedAt: Long = System.currentTimeMillis(),
    val connectedAt: Long? = null,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    /** True when no TURN relay server is available (STUN-only fallback). */
    val relayUnavailable: Boolean = false,
    /** True when the caller is not a known contact (fetched on demand). */
    val isNewContact: Boolean = false,
)

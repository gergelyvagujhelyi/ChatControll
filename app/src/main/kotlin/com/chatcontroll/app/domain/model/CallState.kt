package com.chatcontroll.app.domain.model

enum class CallDirection { OUTGOING, INCOMING }

enum class CallStatus {
    IDLE,
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    FAILED,
    REJECTED,
    BUSY,
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
)

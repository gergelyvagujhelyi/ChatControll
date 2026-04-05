package com.chatcontroll.app.domain.model

enum class MessageState {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    SEEN,
    /** Ciphertext was received but could not be decrypted after all retries. */
    DECRYPT_FAILED,
    /** Message was rejected (unsigned when required, or unverifiable sender). */
    REJECTED,
    /** Outgoing voice call (plaintext = duration in seconds, "0" if never connected). */
    CALL_OUTGOING,
    /** Incoming voice call that was answered (plaintext = duration in seconds). */
    CALL_INCOMING,
    /** Missed incoming voice call (not answered, rejected, or unavailable). */
    CALL_MISSED;

    val isTerminal: Boolean
        get() = this == DELIVERED || this == SEEN || this == FAILED || this == DECRYPT_FAILED || this == REJECTED
                || isCallEvent

    val isCallEvent: Boolean
        get() = this == CALL_OUTGOING || this == CALL_INCOMING || this == CALL_MISSED
}

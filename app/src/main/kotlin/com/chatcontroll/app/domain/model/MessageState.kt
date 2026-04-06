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
    /** Outgoing voice call that was answered (plaintext = duration in seconds). */
    CALL_OUTGOING,
    /** Outgoing voice call that was never answered (rejected, busy, no answer, failed). */
    CALL_OUTGOING_MISSED,
    /** Incoming voice call that was answered (plaintext = duration in seconds). */
    CALL_INCOMING,
    /** Missed incoming voice call (not answered, rejected, or unavailable). */
    CALL_MISSED,
    /** Local user rotated their identity keys. */
    KEY_ROTATED_LOCAL,
    /** Remote peer rotated their identity keys (session_reset received). */
    KEY_ROTATED_REMOTE,
    /** Remote peer deleted their account. */
    ACCOUNT_DELETED;

    val isTerminal: Boolean
        get() = this == DELIVERED || this == SEEN || this == FAILED || this == DECRYPT_FAILED || this == REJECTED
                || isCallEvent || isKeyChangeEvent || this == ACCOUNT_DELETED

    val isCallEvent: Boolean
        get() = this == CALL_OUTGOING || this == CALL_OUTGOING_MISSED
                || this == CALL_INCOMING || this == CALL_MISSED

    val isKeyChangeEvent: Boolean
        get() = this == KEY_ROTATED_LOCAL || this == KEY_ROTATED_REMOTE || this == ACCOUNT_DELETED
}

package com.chatcontroll.app.domain.model

enum class MessageState {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    SEEN,
    /** Ciphertext was received but could not be decrypted after all retries. */
    DECRYPT_FAILED;

    val isTerminal: Boolean
        get() = this == DELIVERED || this == SEEN || this == FAILED || this == DECRYPT_FAILED
}

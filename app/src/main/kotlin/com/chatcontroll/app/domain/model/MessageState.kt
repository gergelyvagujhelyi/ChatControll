package com.chatcontroll.app.domain.model

enum class MessageState {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    SEEN;

    val isTerminal: Boolean
        get() = this == DELIVERED || this == SEEN || this == FAILED
}

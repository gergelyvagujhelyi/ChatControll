package com.chatcontroll.app.domain.model

import kotlinx.datetime.Instant

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val plaintext: String,
    val state: MessageState,
    val timestamp: Instant,
    val expiresAt: Instant? = null,
    val isOutgoing: Boolean,
)

package com.chatcontroll.app.domain.model

import kotlinx.datetime.Instant

data class Conversation(
    val id: String,
    val contactId: String,
    val contactDisplayName: String,
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Instant?,
    val unreadCount: Int,
    val isEncrypted: Boolean,
    val isApproved: Boolean = true,
    val needsSessionReset: Boolean = false,
    val peerDeleted: Boolean = false,
)

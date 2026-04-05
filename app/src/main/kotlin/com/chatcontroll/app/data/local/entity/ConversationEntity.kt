package com.chatcontroll.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val contactDisplayName: String,
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Long?,
    val unreadCount: Int,
    val isEncrypted: Boolean,
    val isApproved: Boolean = true,
    val needsSessionReset: Boolean = false,
)

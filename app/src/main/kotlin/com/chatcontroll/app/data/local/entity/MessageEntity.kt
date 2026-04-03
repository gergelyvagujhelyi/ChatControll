package com.chatcontroll.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId", "timestamp"),
        Index("state"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val encryptedBody: ByteArray,
    val nonce: ByteArray,
    val plaintext: String = "",
    val state: String,
    val timestamp: Long,
    val expiresAt: Long?,
    val isOutgoing: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

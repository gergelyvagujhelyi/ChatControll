package com.chatcontroll.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val publicIdentityKey: ByteArray,
    val publicSigningKey: ByteArray,
    val pqcSigningKey: ByteArray = ByteArray(0),
    val verified: Boolean = false,
    val pqcEstablished: Boolean = false,
    val signatureRequired: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return userId == other.userId &&
            publicIdentityKey.contentEquals(other.publicIdentityKey) &&
            publicSigningKey.contentEquals(other.publicSigningKey)
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + publicIdentityKey.contentHashCode()
        result = 31 * result + publicSigningKey.contentHashCode()
        return result
    }
}

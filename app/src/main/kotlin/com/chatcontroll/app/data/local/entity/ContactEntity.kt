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
            displayName == other.displayName &&
            publicIdentityKey.contentEquals(other.publicIdentityKey) &&
            publicSigningKey.contentEquals(other.publicSigningKey) &&
            pqcSigningKey.contentEquals(other.pqcSigningKey) &&
            verified == other.verified &&
            pqcEstablished == other.pqcEstablished &&
            signatureRequired == other.signatureRequired
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicIdentityKey.contentHashCode()
        result = 31 * result + publicSigningKey.contentHashCode()
        result = 31 * result + pqcSigningKey.contentHashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + pqcEstablished.hashCode()
        result = 31 * result + signatureRequired.hashCode()
        return result
    }
}

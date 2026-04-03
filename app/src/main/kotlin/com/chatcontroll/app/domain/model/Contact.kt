package com.chatcontroll.app.domain.model

data class Contact(
    val userId: String,
    val displayName: String,
    val publicIdentityKey: ByteArray,
    val publicSigningKey: ByteArray,
    val verified: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}

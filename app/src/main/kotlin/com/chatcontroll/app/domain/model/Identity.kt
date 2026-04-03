package com.chatcontroll.app.domain.model

import kotlinx.datetime.Instant

/**
 * The local user's cryptographic identity.
 * Generated on first launch — no PII required.
 */
data class Identity(
    val userId: String,
    val publicSigningKey: ByteArray,
    val publicIdentityKey: ByteArray,
    val createdAt: Instant,
    val shareCode: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}

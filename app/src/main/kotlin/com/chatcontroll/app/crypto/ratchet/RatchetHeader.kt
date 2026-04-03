package com.chatcontroll.app.crypto.ratchet

import kotlinx.serialization.Serializable

/**
 * Header attached to each Double Ratchet message.
 *
 * Contains the sender's current ratchet public key and chain indices,
 * allowing the receiver to perform DH ratchet steps and derive the
 * correct message key.
 */
@Serializable
data class RatchetHeader(
    /** Sender's current DH ratchet public key (X25519, X.509 encoded, base64). */
    val publicKey: String,
    /** Index in the previous sending chain (for skipped message handling). */
    val previousChainLength: Int,
    /** Index in the current sending chain. */
    val messageNumber: Int,
)

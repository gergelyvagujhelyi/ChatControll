package com.chatcontroll.app.crypto.ratchet

/**
 * The mutable state of a Double Ratchet session between two peers.
 *
 * This state evolves with each sent/received message:
 * - Sending a message advances the sending chain (symmetric ratchet).
 * - Receiving a message with a new DH ratchet key triggers a DH ratchet step,
 *   creating new sending and receiving chains (providing forward secrecy).
 *
 * Security note: This state contains sensitive key material.
 * It must be stored encrypted and cleared from memory when no longer needed.
 */
data class RatchetState(
    /** Our current DH ratchet keypair (public + private, X25519). */
    var dhKeyPair: DhKeyPair,
    /** The remote party's current DH ratchet public key. Null before first message received. */
    var remoteDhPublicKey: ByteArray?,
    /** Root key — used to derive new chain keys during DH ratchet steps. */
    var rootKey: ByteArray,
    /** Current sending chain key. */
    var sendingChainKey: ChainKey?,
    /** Current receiving chain key. */
    var receivingChainKey: ChainKey?,
    /** Number of messages sent in the previous sending chain (for header). */
    var previousSendingChainLength: Int = 0,
    /** Skipped message keys we haven't used yet (for out-of-order delivery).
     *  Key: (ratchetPublicKey hex, messageNumber) → message key bytes. */
    val skippedMessageKeys: MutableMap<Pair<String, Int>, ByteArray> = mutableMapOf(),
    /** Timestamps (epoch millis) for each skipped key, for expiration. */
    val skippedKeyTimestamps: MutableMap<Pair<String, Int>, Long> = mutableMapOf(),
    /** KEM ciphertext to attach to the first outbound message header (initiator only). */
    var pendingKemCiphertext: ByteArray? = null,
    /** Whether PQC secret has been mixed into this session's root key. */
    var pqcEstablished: Boolean = false,
) {
    companion object {
        /** Maximum number of skipped message keys to store per chain.
         *  Limits memory from a malicious peer claiming high message numbers,
         *  while tolerating legitimate reorder storms on flaky networks. */
        const val MAX_SKIP = 256
        /** Skipped message keys older than this are purged (7 days). */
        const val SKIPPED_KEY_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

data class DhKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DhKeyPair) return false
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode()
}

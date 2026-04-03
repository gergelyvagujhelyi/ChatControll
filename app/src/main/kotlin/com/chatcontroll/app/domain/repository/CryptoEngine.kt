package com.chatcontroll.app.domain.repository

/**
 * Abstraction over the hybrid classical + post-quantum cryptography layer.
 *
 * All implementations MUST:
 * - Never log plaintext, keys, or sensitive identifiers.
 * - Clear key material from memory when no longer needed.
 * - Support algorithm agility so classical or PQC primitives can be swapped.
 */
interface CryptoEngine {

    /** Generate a new identity keypair (signing + key-agreement). Returns the public keys. */
    suspend fun generateIdentity(): KeyPair

    /** Perform hybrid key establishment with a peer's public key bundle.
     *  Combines X25519 + ML-KEM encapsulation results via HKDF.
     *  @param inboundKemCiphertext KEM ciphertext from the initiator's first message (responder only). */
    suspend fun establishSession(
        localIdentity: KeyPair,
        remotePublicBundle: PublicKeyBundle,
        inboundKemCiphertext: ByteArray? = null,
    ): SessionKeys

    /** Encrypt a plaintext message for the given session. Returns ciphertext + nonce. */
    suspend fun encrypt(sessionKeys: SessionKeys, plaintext: ByteArray): EncryptedEnvelope

    /** Decrypt a received envelope using session keys. */
    suspend fun decrypt(sessionKeys: SessionKeys, envelope: EncryptedEnvelope): ByteArray

    /** Sign arbitrary data with the local signing key. */
    suspend fun sign(data: ByteArray): ByteArray

    /** Verify a signature against a public signing key. */
    suspend fun verify(data: ByteArray, signature: ByteArray, publicSigningKey: ByteArray): Boolean

    /** Derive a deterministic share code from the public identity key. */
    fun deriveShareCode(publicIdentityKey: ByteArray): String
}

data class KeyPair(
    val publicSigningKey: ByteArray,
    val privateSigningKey: ByteArray,
    val publicIdentityKey: ByteArray,
    val privateIdentityKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return publicIdentityKey.contentEquals(other.publicIdentityKey)
    }

    override fun hashCode(): Int = publicIdentityKey.contentHashCode()
}

data class PublicKeyBundle(
    val publicSigningKey: ByteArray,
    val publicIdentityKey: ByteArray,
    /** ML-KEM encapsulation key (post-quantum). May be empty if peer doesn't support PQC. */
    val pqcEncapsulationKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublicKeyBundle) return false
        return publicIdentityKey.contentEquals(other.publicIdentityKey)
    }

    override fun hashCode(): Int = publicIdentityKey.contentHashCode()
}

data class SessionKeys(
    val sendKey: ByteArray,
    val receiveKey: ByteArray,
    val sessionId: String,
    val pqcEstablished: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionKeys) return false
        return sessionId == other.sessionId
    }

    override fun hashCode(): Int = sessionId.hashCode()
}

data class EncryptedEnvelope(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val ephemeralPublicKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedEnvelope) return false
        return ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = ciphertext.contentHashCode()
}

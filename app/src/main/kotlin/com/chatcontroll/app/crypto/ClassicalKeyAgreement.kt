package com.chatcontroll.app.crypto

/**
 * Interface for classical (non-PQC) cryptographic operations.
 * Default implementation uses X25519 for key agreement and Ed25519 for signing.
 */
interface ClassicalKeyAgreement {
    /** Generate Ed25519 signing keypair. Returns (publicKey, privateKey). */
    fun generateSigningKeyPair(): Pair<ByteArray, ByteArray>

    /** Generate X25519 key-agreement keypair. Returns (publicKey, privateKey). */
    fun generateKeyAgreementKeyPair(): Pair<ByteArray, ByteArray>

    /** X25519 Diffie-Hellman. */
    fun agree(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray

    /** Ed25519 sign. Uses the locally stored signing private key. */
    fun sign(data: ByteArray): ByteArray

    /** Ed25519 verify against a public key. */
    fun verify(data: ByteArray, signature: ByteArray, publicSigningKey: ByteArray): Boolean
}

package com.chatcontroll.app.crypto

/**
 * Abstraction for post-quantum cryptographic operations.
 *
 * KEM: ML-KEM-768 (FIPS 203) for key encapsulation.
 * DSA: ML-DSA-65 (FIPS 204) for digital signatures.
 */
interface PqcProvider {

    /** Generate an ML-KEM keypair. Returns (encapsulationKey, decapsulationKey). */
    fun generateKemKeyPair(): KemKeyPair

    /** Encapsulate: produce (ciphertext, sharedSecret) for the given encapsulation key. */
    fun encapsulate(encapsulationKey: ByteArray): KemEncapsulation

    /** Decapsulate: recover sharedSecret from ciphertext using the decapsulation key. */
    fun decapsulate(ciphertext: ByteArray, decapsulationKey: ByteArray): ByteArray

    /** Generate an ML-DSA-65 signing keypair. */
    fun generateSigningKeyPair(): DsaKeyPair

    /** Sign data with an ML-DSA-65 private key. */
    fun sign(data: ByteArray, privateKey: ByteArray): ByteArray

    /** Verify an ML-DSA-65 signature against a public key. */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

data class DsaKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DsaKeyPair) return false
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode()
}

data class KemKeyPair(
    val encapsulationKey: ByteArray,
    val decapsulationKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KemKeyPair) return false
        return encapsulationKey.contentEquals(other.encapsulationKey)
    }

    override fun hashCode(): Int = encapsulationKey.contentHashCode()
}

data class KemEncapsulation(
    val ciphertext: ByteArray,
    val sharedSecret: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KemEncapsulation) return false
        return ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = ciphertext.contentHashCode()
}

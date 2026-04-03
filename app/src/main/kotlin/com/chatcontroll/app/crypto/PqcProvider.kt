package com.chatcontroll.app.crypto

/**
 * Abstraction for post-quantum cryptographic operations.
 *
 * Production implementation should use ML-KEM-768 (FIPS 203).
 * Swap this interface's implementation when a production-ready
 * Android ML-KEM library becomes available.
 */
interface PqcProvider {

    /** Generate an ML-KEM keypair. Returns (encapsulationKey, decapsulationKey). */
    fun generateKemKeyPair(): KemKeyPair

    /** Encapsulate: produce (ciphertext, sharedSecret) for the given encapsulation key. */
    fun encapsulate(encapsulationKey: ByteArray): KemEncapsulation

    /** Decapsulate: recover sharedSecret from ciphertext using the decapsulation key. */
    fun decapsulate(ciphertext: ByteArray, decapsulationKey: ByteArray): ByteArray
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

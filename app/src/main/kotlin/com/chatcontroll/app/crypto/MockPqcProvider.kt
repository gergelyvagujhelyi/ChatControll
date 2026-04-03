package com.chatcontroll.app.crypto

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TODO: Replace with a production ML-KEM-768 (FIPS 203) implementation
 * when a mature, audited Android library is available.
 *
 * Candidates to evaluate:
 * - Bouncy Castle's "MLKEM" provider (post 1.79)
 * - liboqs JNI bindings
 * - Google Tink PQC extensions
 *
 * This mock simulates the KEM interface with HMAC-based key derivation
 * for testing and development. It provides NO post-quantum security.
 * The ciphertext is a random nonce; the shared secret is HMAC(key, nonce).
 *
 * SECURITY WARNING: This is NOT quantum-resistant. It exists only to
 * validate the hybrid key establishment flow. The classical X25519
 * component provides the actual security in this configuration.
 */
@Singleton
class MockPqcProvider @Inject constructor() : PqcProvider {

    private val secureRandom = SecureRandom()

    override fun generateKemKeyPair(): KemKeyPair {
        val encapsulationKey = ByteArray(MOCK_KEY_SIZE).also { secureRandom.nextBytes(it) }
        val decapsulationKey = ByteArray(MOCK_KEY_SIZE).also { secureRandom.nextBytes(it) }
        return KemKeyPair(encapsulationKey, decapsulationKey)
    }

    override fun encapsulate(encapsulationKey: ByteArray): KemEncapsulation {
        val nonce = ByteArray(MOCK_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(encapsulationKey, "HmacSHA256"))
        mac.update(nonce)
        val sharedSecret = mac.doFinal()
        return KemEncapsulation(ciphertext = nonce, sharedSecret = sharedSecret)
    }

    override fun decapsulate(ciphertext: ByteArray, decapsulationKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(decapsulationKey, "HmacSHA256"))
        mac.update(ciphertext)
        return mac.doFinal()
    }

    companion object {
        private const val MOCK_KEY_SIZE = 32
        private const val MOCK_NONCE_SIZE = 32
    }
}

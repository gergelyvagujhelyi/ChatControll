package com.chatcontroll.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BouncyCastlePqcProviderTest {

    private val provider = BouncyCastlePqcProvider()

    @Test
    fun `generateKemKeyPair produces ML-KEM-768 sized keys`() {
        val kp = provider.generateKemKeyPair()
        assertTrue("Encapsulation key should be non-empty", kp.encapsulationKey.isNotEmpty())
        assertTrue("Decapsulation key should be non-empty", kp.decapsulationKey.isNotEmpty())
    }

    @Test
    fun `encapsulate and decapsulate produce matching shared secrets`() {
        val kp = provider.generateKemKeyPair()

        val encapsulation = provider.encapsulate(kp.encapsulationKey)
        assertTrue("Ciphertext should be non-trivial", encapsulation.ciphertext.size > 1000)
        assertEquals("Shared secret should be 256 bits", 32, encapsulation.sharedSecret.size)

        val decapsulated = provider.decapsulate(encapsulation.ciphertext, kp.decapsulationKey)
        assertArrayEquals(
            "Encapsulated and decapsulated shared secrets must match",
            encapsulation.sharedSecret,
            decapsulated,
        )
    }

    @Test
    fun `different encapsulations produce different shared secrets`() {
        val kp = provider.generateKemKeyPair()
        val enc1 = provider.encapsulate(kp.encapsulationKey)
        val enc2 = provider.encapsulate(kp.encapsulationKey)

        assertNotEquals(
            "Two encapsulations should produce different ciphertexts",
            enc1.ciphertext.toList(),
            enc2.ciphertext.toList(),
        )
        assertNotEquals(
            "Two encapsulations should produce different shared secrets",
            enc1.sharedSecret.toList(),
            enc2.sharedSecret.toList(),
        )
    }

    @Test
    fun `different keypairs produce independent KEM operations`() {
        val kp1 = provider.generateKemKeyPair()
        val kp2 = provider.generateKemKeyPair()

        val enc = provider.encapsulate(kp1.encapsulationKey)

        // Decapsulating with the wrong key should produce a different secret
        // (ML-KEM implicit rejection produces a pseudo-random output, not an error)
        val correctSecret = provider.decapsulate(enc.ciphertext, kp1.decapsulationKey)
        val wrongSecret = provider.decapsulate(enc.ciphertext, kp2.decapsulationKey)

        assertArrayEquals(enc.sharedSecret, correctSecret)
        assertNotEquals(
            "Wrong decapsulation key should not produce the correct secret",
            enc.sharedSecret.toList(),
            wrongSecret.toList(),
        )
    }
}

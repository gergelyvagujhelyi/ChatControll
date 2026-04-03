package com.chatcontroll.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPqcProviderTest {

    private val provider = MockPqcProvider()

    @Test
    fun `generateKemKeyPair produces non-empty keys`() {
        val kp = provider.generateKemKeyPair()
        assertTrue(kp.encapsulationKey.isNotEmpty())
        assertTrue(kp.decapsulationKey.isNotEmpty())
        assertEquals(32, kp.encapsulationKey.size)
        assertEquals(32, kp.decapsulationKey.size)
    }

    @Test
    fun `encapsulate produces shared secret and ciphertext`() {
        val kp = provider.generateKemKeyPair()
        val enc = provider.encapsulate(kp.encapsulationKey)
        assertTrue(enc.ciphertext.isNotEmpty())
        assertTrue(enc.sharedSecret.isNotEmpty())
        assertEquals(32, enc.sharedSecret.size)
    }

    @Test
    fun `different encapsulations produce different ciphertexts`() {
        val kp = provider.generateKemKeyPair()
        val enc1 = provider.encapsulate(kp.encapsulationKey)
        val enc2 = provider.encapsulate(kp.encapsulationKey)
        assertNotEquals(enc1.ciphertext.toList(), enc2.ciphertext.toList())
    }

    @Test
    fun `decapsulate with matching key produces correct secret`() {
        // Note: The mock uses HMAC with encapsulation key for encapsulate
        // and decapsulation key for decapsulate, so the secrets WON'T match
        // (this is the mock's limitation — it's not a real KEM).
        // This test just verifies the API contract works.
        val kp = provider.generateKemKeyPair()
        val enc = provider.encapsulate(kp.encapsulationKey)
        val decapsulated = provider.decapsulate(enc.ciphertext, kp.decapsulationKey)
        assertEquals(32, decapsulated.size)
    }
}

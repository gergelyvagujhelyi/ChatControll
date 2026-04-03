package com.chatcontroll.app.crypto.ratchet

import com.chatcontroll.app.crypto.ClassicalKeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class DoubleRatchetTest {

    private lateinit var ratchet: DoubleRatchet
    private lateinit var ka: TestClassicalKeyAgreement
    private lateinit var sharedSecret: ByteArray

    @Before
    fun setup() {
        ka = TestClassicalKeyAgreement()
        ratchet = DoubleRatchet(ka)
        sharedSecret = ByteArray(32) { it.toByte() }
    }

    @Test
    fun `basic encrypt decrypt round trip`() {
        val bobKeyPair = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKeyPair.first, privateKey = bobKeyPair.second)

        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice sends a message
        val plaintext = "Hello Bob!".toByteArray()
        val (header, ciphertext) = ratchet.encrypt(aliceState, plaintext)

        // Bob decrypts
        val decrypted = ratchet.decrypt(bobState, header, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `multiple messages in same direction`() {
        val bobKeyPair = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKeyPair.first, privateKey = bobKeyPair.second)

        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        val messages = listOf("msg1", "msg2", "msg3")
        val encrypted = messages.map { ratchet.encrypt(aliceState, it.toByteArray()) }

        for ((i, msg) in messages.withIndex()) {
            val (header, ciphertext) = encrypted[i]
            val decrypted = String(ratchet.decrypt(bobState, header, ciphertext))
            assertEquals(msg, decrypted)
        }
    }

    @Test
    fun `ping-pong conversation`() {
        val bobKeyPair = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKeyPair.first, privateKey = bobKeyPair.second)

        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice -> Bob
        val (h1, c1) = ratchet.encrypt(aliceState, "Hello".toByteArray())
        assertEquals("Hello", String(ratchet.decrypt(bobState, h1, c1)))

        // Bob -> Alice
        val (h2, c2) = ratchet.encrypt(bobState, "Hi there".toByteArray())
        assertEquals("Hi there", String(ratchet.decrypt(aliceState, h2, c2)))

        // Alice -> Bob again
        val (h3, c3) = ratchet.encrypt(aliceState, "How are you?".toByteArray())
        assertEquals("How are you?", String(ratchet.decrypt(bobState, h3, c3)))

        // Bob -> Alice again
        val (h4, c4) = ratchet.encrypt(bobState, "Good!".toByteArray())
        assertEquals("Good!", String(ratchet.decrypt(aliceState, h4, c4)))
    }

    @Test
    fun `each message uses different key (ciphertext differs)`() {
        val bobKeyPair = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKeyPair.first, privateKey = bobKeyPair.second)

        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)

        val plaintext = "Same message".toByteArray()
        val (_, c1) = ratchet.encrypt(aliceState, plaintext)
        val (_, c2) = ratchet.encrypt(aliceState, plaintext)

        assertNotEquals(c1.toList(), c2.toList())
    }

    @Test
    fun `chain key advances correctly`() {
        val key = ByteArray(32) { 0x42 }
        val chain = ChainKey(key, 0)
        val next = chain.next()

        assertEquals(1, next.index)
        assertNotEquals(key.toList(), next.key.toList())

        // Message key and chain key should differ
        assertNotEquals(chain.messageKey().toList(), next.key.toList())
    }

    @Test
    fun `chain key derivation is deterministic`() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val chain1 = ChainKey(key.clone(), 0)
        val chain2 = ChainKey(key.clone(), 0)

        assertArrayEquals(chain1.messageKey(), chain2.messageKey())
        assertArrayEquals(chain1.next().key, chain2.next().key)
    }
}

/**
 * Test-only classical key agreement that works without Android runtime.
 * Uses JVM JCA providers for X25519 and Ed25519.
 */
private class TestClassicalKeyAgreement : ClassicalKeyAgreement {
    override fun generateSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        return kp.public.encoded to kp.private.encoded
    }

    override fun generateKeyAgreementKeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        return kp.public.encoded to kp.private.encoded
    }

    override fun agree(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(remotePublicKey))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    override fun sign(data: ByteArray): ByteArray {
        throw UnsupportedOperationException("Not used in ratchet tests")
    }

    override fun verify(data: ByteArray, signature: ByteArray, publicSigningKey: ByteArray): Boolean {
        val kf = KeyFactory.getInstance("Ed25519")
        val pubKey = kf.generatePublic(X509EncodedKeySpec(publicSigningKey))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(pubKey)
        sig.update(data)
        return sig.verify(signature)
    }
}

package com.chatcontroll.app.crypto.ratchet

import com.chatcontroll.app.crypto.ClassicalKeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Edge case tests for the Double Ratchet: out-of-order delivery,
 * MAX_SKIP boundary, skipped key limits, and tampered headers.
 */
class DoubleRatchetEdgeCaseTest {

    private lateinit var ratchet: DoubleRatchet
    private lateinit var ka: EdgeCaseTestKeyAgreement
    private lateinit var sharedSecret: ByteArray

    @Before
    fun setup() {
        ka = EdgeCaseTestKeyAgreement()
        ratchet = DoubleRatchet(ka)
        sharedSecret = ByteArray(32) { it.toByte() }
    }

    // ── Out-of-order delivery ─────────────────────────────────────────

    @Test
    fun `out-of-order messages within same chain decrypt correctly`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice sends 3 messages
        val (h1, c1) = ratchet.encrypt(aliceState, "msg1".toByteArray())
        val (h2, c2) = ratchet.encrypt(aliceState, "msg2".toByteArray())
        val (h3, c3) = ratchet.encrypt(aliceState, "msg3".toByteArray())

        // Bob receives them out of order: 3, 1, 2
        assertEquals("msg3", String(ratchet.decrypt(bobState, h3, c3)))
        assertEquals("msg1", String(ratchet.decrypt(bobState, h1, c1)))
        assertEquals("msg2", String(ratchet.decrypt(bobState, h2, c2)))
    }

    @Test
    fun `out-of-order across DH ratchet steps`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice sends msg1
        val (h1, c1) = ratchet.encrypt(aliceState, "alice-1".toByteArray())

        // Bob receives and replies (triggers DH ratchet)
        ratchet.decrypt(bobState, h1, c1)
        val (h2, c2) = ratchet.encrypt(bobState, "bob-1".toByteArray())

        // Alice receives Bob's reply (triggers DH ratchet)
        ratchet.decrypt(aliceState, h2, c2)

        // Alice sends two more messages on new chain
        val (h3, c3) = ratchet.encrypt(aliceState, "alice-2".toByteArray())
        val (h4, c4) = ratchet.encrypt(aliceState, "alice-3".toByteArray())

        // Bob receives them in reverse order
        assertEquals("alice-3", String(ratchet.decrypt(bobState, h4, c4)))
        assertEquals("alice-2", String(ratchet.decrypt(bobState, h3, c3)))
    }

    // ── MAX_SKIP boundary ─────────────────────────────────────────────

    @Test
    fun `exactly MAX_SKIP skipped messages succeeds`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice sends MAX_SKIP + 1 messages, Bob only decrypts the last one
        val encrypted = mutableListOf<Pair<RatchetHeader, ByteArray>>()
        for (i in 0..RatchetState.MAX_SKIP) {
            encrypted.add(ratchet.encrypt(aliceState, "msg-$i".toByteArray()))
        }

        // Decrypt only the last message — skips exactly MAX_SKIP
        val (lastH, lastC) = encrypted.last()
        val result = String(ratchet.decrypt(bobState, lastH, lastC))
        assertEquals("msg-${RatchetState.MAX_SKIP}", result)
    }

    @Test
    fun `exceeding MAX_SKIP throws SecurityException`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice sends MAX_SKIP + 2 messages
        val encrypted = mutableListOf<Pair<RatchetHeader, ByteArray>>()
        for (i in 0..RatchetState.MAX_SKIP + 1) {
            encrypted.add(ratchet.encrypt(aliceState, "msg-$i".toByteArray()))
        }

        // Trying to decrypt message MAX_SKIP+1 (skipping MAX_SKIP+1 messages) should fail
        val (lastH, lastC) = encrypted.last()
        assertThrows(SecurityException::class.java) {
            ratchet.decrypt(bobState, lastH, lastC)
        }
    }

    // ── Replay protection ─────────────────────────────────────────────

    @Test
    fun `replaying a consumed message fails`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        val (h, c) = ratchet.encrypt(aliceState, "hello".toByteArray())
        ratchet.decrypt(bobState, h, c) // first decrypt succeeds

        // Replaying the same message: the key was consumed and chain advanced,
        // so this message number is now behind the chain index → treated as
        // skip that goes backwards, which throws
        assertThrows(SecurityException::class.java) {
            ratchet.decrypt(bobState, h, c)
        }
    }

    // ── Tampered ciphertext ───────────────────────────────────────────

    @Test
    fun `tampered ciphertext fails GCM authentication`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        val (h, c) = ratchet.encrypt(aliceState, "secret".toByteArray())

        // Flip a byte in the ciphertext
        val tampered = c.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0xFF).toByte()

        assertThrows(Exception::class.java) {
            ratchet.decrypt(bobState, h, tampered)
        }
    }

    // ── Skipped keys from old chain still work ────────────────────────

    @Test
    fun `skipped message from previous chain decrypts after ratchet advance`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice sends two messages
        val (h1, c1) = ratchet.encrypt(aliceState, "first".toByteArray())
        val (h2, c2) = ratchet.encrypt(aliceState, "second".toByteArray())

        // Bob only decrypts the second (skipping first)
        assertEquals("second", String(ratchet.decrypt(bobState, h2, c2)))

        // Bob replies, advancing the DH ratchet
        val (bh, bc) = ratchet.encrypt(bobState, "reply".toByteArray())
        assertEquals("reply", String(ratchet.decrypt(aliceState, bh, bc)))

        // Now Bob can still decrypt the skipped first message
        assertEquals("first", String(ratchet.decrypt(bobState, h1, c1)))
    }

    // ── Forward secrecy: keys differ per message ──────────────────────

    @Test
    fun `DH ratchet step produces different chain keys`() {
        val bobKp = ka.generateKeyAgreementKeyPair()
        val bobDh = DhKeyPair(publicKey = bobKp.first, privateKey = bobKp.second)
        val aliceState = ratchet.initAlice(sharedSecret, bobDh.publicKey)
        val bobState = ratchet.initBob(sharedSecret, bobDh)

        // Alice -> Bob (chain 1)
        val (h1, c1) = ratchet.encrypt(aliceState, "chain1".toByteArray())
        ratchet.decrypt(bobState, h1, c1)

        val rootKeyAfterFirst = aliceState.rootKey.copyOf()

        // Bob -> Alice (DH ratchet step)
        val (h2, c2) = ratchet.encrypt(bobState, "reply".toByteArray())
        ratchet.decrypt(aliceState, h2, c2)

        // Alice -> Bob again (chain 2, after ratchet)
        val (h3, c3) = ratchet.encrypt(aliceState, "chain2".toByteArray())

        // Root key should have changed after the DH ratchet steps
        assertTrue(
            "Root key should change after DH ratchet",
            !rootKeyAfterFirst.contentEquals(aliceState.rootKey),
        )

        // Both messages still decrypt correctly
        assertEquals("chain2", String(ratchet.decrypt(bobState, h3, c3)))
    }
}

/**
 * Test-only classical key agreement that works without Android runtime.
 */
private class EdgeCaseTestKeyAgreement : ClassicalKeyAgreement {
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

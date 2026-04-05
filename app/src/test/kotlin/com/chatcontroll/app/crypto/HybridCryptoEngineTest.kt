package com.chatcontroll.app.crypto

import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class HybridCryptoEngineTest {

    private lateinit var engine: HybridCryptoEngine
    private lateinit var classicalKa: ClassicalKeyAgreement
    private lateinit var pqcProvider: PqcProvider
    private lateinit var keyManager: KeyManager

    @Before
    fun setup() {
        classicalKa = JvmClassicalKeyAgreement()
        pqcProvider = MockPqcProvider()
        keyManager = io.mockk.mockk(relaxed = true)
        engine = HybridCryptoEngine(classicalKa, pqcProvider, keyManager)
    }

    @Test
    fun `generateIdentity produces non-empty keys`() = runTest {
        val keyPair = engine.generateIdentity()
        assertTrue(keyPair.publicSigningKey.isNotEmpty())
        assertTrue(keyPair.privateSigningKey.isNotEmpty())
        assertTrue(keyPair.publicIdentityKey.isNotEmpty())
        assertTrue(keyPair.privateIdentityKey.isNotEmpty())
    }

    @Test
    fun `encrypt then decrypt round-trips correctly`() = runTest {
        val alice = engine.generateIdentity()
        val bob = engine.generateIdentity()

        val aliceSession = engine.establishSession(
            alice,
            PublicKeyBundle(
                publicSigningKey = bob.publicSigningKey,
                publicIdentityKey = bob.publicIdentityKey,
            ),
        )

        val plaintext = "Hello, Bob!".toByteArray()
        val envelope = engine.encrypt(aliceSession, plaintext)

        // Bob decrypts using his session keys (swap send/receive)
        val bobSession = engine.establishSession(
            bob,
            PublicKeyBundle(
                publicSigningKey = alice.publicSigningKey,
                publicIdentityKey = alice.publicIdentityKey,
            ),
        )

        // Bob's receiveKey matches Alice's sendKey, so Bob can decrypt Alice's message.
        val decrypted = engine.decrypt(bobSession, envelope)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertexts for same plaintext`() = runTest {
        val alice = engine.generateIdentity()
        val bob = engine.generateIdentity()

        val session = engine.establishSession(
            alice,
            PublicKeyBundle(
                publicSigningKey = bob.publicSigningKey,
                publicIdentityKey = bob.publicIdentityKey,
            ),
        )

        val plaintext = "Same message".toByteArray()
        val envelope1 = engine.encrypt(session, plaintext)
        val envelope2 = engine.encrypt(session, plaintext)

        // Different nonces should produce different ciphertexts
        assertNotEquals(envelope1.ciphertext.toList(), envelope2.ciphertext.toList())
        assertNotEquals(envelope1.nonce.toList(), envelope2.nonce.toList())
    }

    @Test
    fun `deriveShareCode is deterministic`() = runTest {
        val keyPair = engine.generateIdentity()
        val code1 = engine.deriveShareCode(keyPair.publicIdentityKey)
        val code2 = engine.deriveShareCode(keyPair.publicIdentityKey)
        assertEquals(code1, code2)
    }

    @Test
    fun `deriveShareCode is different for different keys`() = runTest {
        val kp1 = engine.generateIdentity()
        val kp2 = engine.generateIdentity()
        val code1 = engine.deriveShareCode(kp1.publicIdentityKey)
        val code2 = engine.deriveShareCode(kp2.publicIdentityKey)
        assertNotEquals(code1, code2)
    }

    @Test
    fun `hkdf produces expected length output`() {
        val result = hkdfSha256(
            ikm = "test".toByteArray(),
            salt = "salt".toByteArray(),
            info = "info".toByteArray(),
            length = 64,
        )
        assertEquals(64, result.size)
    }

    @Test
    fun `hkdf is deterministic`() {
        val a = hkdfSha256("ikm".toByteArray(), "s".toByteArray(), "i".toByteArray(), 32)
        val b = hkdfSha256("ikm".toByteArray(), "s".toByteArray(), "i".toByteArray(), 32)
        assertArrayEquals(a, b)
    }
}

/**
 * JVM-compatible implementation of ClassicalKeyAgreement for unit tests
 * (avoids Android Keystore dependencies).
 */
private class JvmClassicalKeyAgreement : ClassicalKeyAgreement {

    private var signingPrivateKey: ByteArray? = null

    override fun generateSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        signingPrivateKey = kp.private.encoded
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
        val privKeyBytes = signingPrivateKey
            ?: throw IllegalStateException("No signing key generated")
        val kf = KeyFactory.getInstance("Ed25519")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privKeyBytes))
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privKey)
        sig.update(data)
        return sig.sign()
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

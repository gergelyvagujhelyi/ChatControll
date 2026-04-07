package com.chatcontroll.app.crypto

import java.util.Base64
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.EncryptedEnvelope
import com.chatcontroll.app.domain.repository.KeyPair
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid cryptography engine combining:
 * - X25519 for classical ECDH key agreement
 * - ML-KEM-768 for post-quantum key establishment (via [PqcProvider])
 * - AES-256-GCM for authenticated encryption of message payloads
 * - HKDF-SHA256 to combine classical and PQ shared secrets
 * - Ed25519 for identity signing
 *
 * The [PqcProvider] abstraction isolates the PQC implementation so it can be
 * swapped when a production-ready Android ML-KEM library becomes available.
 */
@Singleton
class HybridCryptoEngine @Inject constructor(
    private val classicalKeyAgreement: ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
    private val keyManager: KeyManager,
) : CryptoEngine {

    private val secureRandom = SecureRandom()

    override suspend fun generateIdentity(): KeyPair {
        val signing = classicalKeyAgreement.generateSigningKeyPair()
        val identity = classicalKeyAgreement.generateKeyAgreementKeyPair()
        return KeyPair(
            publicSigningKey = signing.first,
            privateSigningKey = signing.second,
            publicIdentityKey = identity.first,
            privateIdentityKey = identity.second,
        )
    }

    override suspend fun establishSession(
        localIdentity: KeyPair,
        remotePublicBundle: PublicKeyBundle,
        inboundKemCiphertext: ByteArray?,
    ): SessionKeys {
        // Classical X25519 shared secret
        val classicalSecret = classicalKeyAgreement.agree(
            privateKey = localIdentity.privateIdentityKey,
            remotePublicKey = remotePublicBundle.publicIdentityKey,
        )

        // Deterministic initiator role
        val isInitiator = localIdentity.publicIdentityKey.joinToString("") { "%02x".format(it) } <
            remotePublicBundle.publicIdentityKey.joinToString("") { "%02x".format(it) }

        // Post-quantum KEM shared secret: either side can encapsulate or decapsulate.
        // Priority: inbound KEM ciphertext → decapsulate; otherwise encapsulate.
        var pqSecret = ByteArray(0)
        if (inboundKemCiphertext != null) {
            val dk = keyManager.getPqcDecapsulationKey()
                ?: throw IllegalStateException("Received KEM ciphertext but no local decapsulation key")
            try {
                pqSecret = pqcProvider.decapsulate(inboundKemCiphertext, dk)
            } finally {
                dk.fill(0)
            }
        } else if (remotePublicBundle.pqcEncapsulationKey.isNotEmpty()) {
            // WARNING: The KEM ciphertext (encapsulation.ciphertext) is discarded here.
            // The remote peer needs it to decapsulate, so PQC session establishment
            // will fail. This engine is a non-production fallback — use
            // RatchetSessionManager for production, which correctly attaches the
            // ciphertext to the first outbound message header.
            val encapsulation = pqcProvider.encapsulate(remotePublicBundle.pqcEncapsulationKey)
            pqSecret = encapsulation.sharedSecret
        }

        // Combine via HKDF, then zeroize inputs.
        // In the PQC path, `+` creates a new array so classicalSecret can be
        // zeroized independently. In classical-only, copyOf() avoids aliasing.
        val isPqcEstablished = pqSecret.isNotEmpty()
        val ikm = if (isPqcEstablished) classicalSecret + pqSecret else classicalSecret.copyOf()
        classicalSecret.fill(0)
        pqSecret.fill(0)
        val combinedSecret = hkdfSha256(
            ikm = ikm,
            salt = "ChatControll-v1-session".toByteArray(),
            info = "hybrid-key-establishment".toByteArray(),
            length = 64,
        )
        ikm.fill(0)

        // Sort keys so both peers compute the same sessionId regardless of role
        val localHex = localIdentity.publicIdentityKey.joinToString("") { "%02x".format(it) }
        val remoteHex = remotePublicBundle.publicIdentityKey.joinToString("") { "%02x".format(it) }
        val orderedKeys = if (localHex < remoteHex) {
            localIdentity.publicIdentityKey + remotePublicBundle.publicIdentityKey
        } else {
            remotePublicBundle.publicIdentityKey + localIdentity.publicIdentityKey
        }
        val sessionId = sha256Hex(orderedKeys)

        // Deterministic key assignment so both sides agree
        val keyA = combinedSecret.copyOfRange(0, 32)
        val keyB = combinedSecret.copyOfRange(32, 64)
        combinedSecret.fill(0)

        return SessionKeys(
            sendKey = if (isInitiator) keyA else keyB,
            receiveKey = if (isInitiator) keyB else keyA,
            sessionId = sessionId,
            pqcEstablished = isPqcEstablished,
        )
    }

    override suspend fun encrypt(sessionKeys: SessionKeys, plaintext: ByteArray): EncryptedEnvelope {
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKeys.sendKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
        )
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedEnvelope(ciphertext = ciphertext, nonce = nonce)
    }

    override suspend fun decrypt(sessionKeys: SessionKeys, envelope: EncryptedEnvelope): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKeys.receiveKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.nonce),
        )
        return cipher.doFinal(envelope.ciphertext)
    }

    override suspend fun sign(data: ByteArray): ByteArray {
        return keyManager.sign(data)
    }

    override suspend fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicSigningKey: ByteArray,
    ): Boolean {
        return classicalKeyAgreement.verify(data, signature, publicSigningKey)
    }

    override fun deriveShareCode(publicIdentityKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicIdentityKey)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash.copyOfRange(0, 12))
    }

    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

/**
 * HKDF-SHA256 extract-and-expand.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")

    // Extract
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)

    // Expand
    val hashLen = 32
    val n = (length + hashLen - 1) / hashLen
    val output = ByteArray(n * hashLen)
    var t = ByteArray(0)
    for (i in 1..n) {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t)
        mac.update(info)
        mac.update(i.toByte())
        val prev = t
        t = mac.doFinal()
        if (prev.isNotEmpty()) prev.fill(0)
        System.arraycopy(t, 0, output, (i - 1) * hashLen, hashLen)
    }
    t.fill(0)
    prk.fill(0)
    val result = output.copyOfRange(0, length)
    output.fill(0)
    return result
}

private fun sha256Hex(data: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }
}

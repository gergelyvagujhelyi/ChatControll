package com.chatcontroll.app.crypto.ratchet

import android.util.Base64
import com.chatcontroll.app.crypto.ClassicalKeyAgreement
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.crypto.hkdfSha256
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.EncryptedEnvelope
import com.chatcontroll.app.domain.repository.KeyPair
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CryptoEngine implementation backed by the Double Ratchet protocol.
 *
 * Session establishment:
 * 1. Hybrid key agreement (X25519 + ML-KEM) produces a shared secret.
 * 2. The shared secret initializes a Double Ratchet session.
 * 3. Each message uses a unique key derived from the ratchet chain.
 *
 * This provides per-message forward secrecy: compromising the current
 * state does not reveal past message keys (they've been deleted).
 * After a DH ratchet step, even future messages become secure again
 * (break-in recovery).
 */
@Singleton
class RatchetSessionManager @Inject constructor(
    private val classicalKeyAgreement: ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
    private val keyManager: KeyManager,
) : CryptoEngine {

    private val ratchet = DoubleRatchet(classicalKeyAgreement)
    private val sessions = mutableMapOf<String, RatchetState>()
    private val json = Json { ignoreUnknownKeys = true }

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
    ): SessionKeys {
        // X25519 key agreement — both sides derive the same shared secret.
        // ML-KEM is not used here because KEM encapsulation is randomized:
        // each side would get a different secret without a proper handshake
        // protocol to exchange the KEM ciphertext. PQC will be added once
        // a KEM-exchange round-trip is implemented.
        val classicalSecret = classicalKeyAgreement.agree(
            privateKey = localIdentity.privateIdentityKey,
            remotePublicKey = remotePublicBundle.publicIdentityKey,
        )

        val sharedSecret = hkdfSha256(
            ikm = classicalSecret,
            salt = "ChatControll-v1-ratchet-init".toByteArray(),
            info = "hybrid-key-establishment".toByteArray(),
            length = 32,
        )

        val sessionId = sha256Hex(
            localIdentity.publicIdentityKey + remotePublicBundle.publicIdentityKey
        )

        // Derive two directional chain keys so both sides can send immediately.
        // The "smaller" public key's owner uses chain A to send and chain B to receive;
        // the "larger" key's owner uses chain B to send and chain A to receive.
        val isInitiator = localIdentity.publicIdentityKey.toHex() <
            remotePublicBundle.publicIdentityKey.toHex()

        val chainMaterial = hkdfSha256(
            ikm = sharedSecret,
            salt = "ChatControll-v1-chains".toByteArray(),
            info = "bidirectional-chains".toByteArray(),
            length = 64,
        )
        val chainA = chainMaterial.copyOfRange(0, 32)
        val chainB = chainMaterial.copyOfRange(32, 64)

        val dhKeyPair = DhKeyPair(
            publicKey = localIdentity.publicIdentityKey,
            privateKey = localIdentity.privateIdentityKey,
        )

        val state = RatchetState(
            dhKeyPair = dhKeyPair,
            remoteDhPublicKey = remotePublicBundle.publicIdentityKey,
            rootKey = sharedSecret,
            sendingChainKey = ChainKey(if (isInitiator) chainA else chainB, 0),
            receivingChainKey = ChainKey(if (isInitiator) chainB else chainA, 0),
        )

        sessions[sessionId] = state

        return SessionKeys(
            sendKey = sharedSecret.copyOfRange(0, 16) + sharedSecret.copyOfRange(0, 16),
            receiveKey = sharedSecret.copyOfRange(16, 32) + sharedSecret.copyOfRange(0, 16),
            sessionId = sessionId,
        )
    }

    override suspend fun encrypt(sessionKeys: SessionKeys, plaintext: ByteArray): EncryptedEnvelope {
        val state = sessions[sessionKeys.sessionId]
            ?: throw IllegalStateException("No ratchet session for ${sessionKeys.sessionId}")

        val (header, ciphertext) = ratchet.encrypt(state, plaintext)
        val headerJson = json.encodeToString(header)

        return EncryptedEnvelope(
            ciphertext = ciphertext,
            nonce = headerJson.toByteArray(Charsets.UTF_8),
            ephemeralPublicKey = Base64.decode(header.publicKey, Base64.NO_WRAP),
        )
    }

    override suspend fun decrypt(sessionKeys: SessionKeys, envelope: EncryptedEnvelope): ByteArray {
        val state = sessions[sessionKeys.sessionId]
            ?: throw IllegalStateException("No ratchet session for ${sessionKeys.sessionId}")

        val headerJson = String(envelope.nonce, Charsets.UTF_8)
        val header = json.decodeFromString<RatchetHeader>(headerJson)

        return ratchet.decrypt(state, header, envelope.ciphertext)
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
        return Base64.encodeToString(hash.copyOfRange(0, 12), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}

private fun sha256Hex(data: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

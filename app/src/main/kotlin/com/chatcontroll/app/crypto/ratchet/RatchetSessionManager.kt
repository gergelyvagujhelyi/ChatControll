package com.chatcontroll.app.crypto.ratchet

import android.util.Base64
import android.util.Log
import com.chatcontroll.app.crypto.ClassicalKeyAgreement
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.crypto.hkdfSha256
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.EncryptedEnvelope
import com.chatcontroll.app.domain.repository.KeyPair
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
    private val sessionsMutex = Mutex()
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
        inboundKemCiphertext: ByteArray?,
    ): SessionKeys {
        // X25519 key agreement — both sides derive the same shared secret.
        val classicalSecret = classicalKeyAgreement.agree(
            privateKey = localIdentity.privateIdentityKey,
            remotePublicKey = remotePublicBundle.publicIdentityKey,
        )

        // Deterministic initiator role: the party with the "smaller" public key
        val isInitiator = localIdentity.publicIdentityKey.toHex() <
            remotePublicBundle.publicIdentityKey.toHex()

        // PQC KEM handshake: initiator encapsulates, responder decapsulates
        var pqcSecret = ByteArray(0)
        var kemCiphertext: ByteArray? = null

        if (isInitiator && remotePublicBundle.pqcEncapsulationKey.isNotEmpty()) {
            // Initiator: encapsulate against the remote's ML-KEM public key
            try {
                val encapsulation = pqcProvider.encapsulate(remotePublicBundle.pqcEncapsulationKey)
                pqcSecret = encapsulation.sharedSecret
                kemCiphertext = encapsulation.ciphertext
            } catch (e: Exception) {
                android.util.Log.w("RatchetSession", "PQC encapsulation failed, classical only: ${e.message}")
            }
        } else if (!isInitiator && inboundKemCiphertext != null) {
            // Responder: decapsulate using our local ML-KEM decapsulation key
            try {
                val decapsulationKey = keyManager.getPqcDecapsulationKey()
                if (decapsulationKey != null) {
                    pqcSecret = pqcProvider.decapsulate(inboundKemCiphertext, decapsulationKey)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatchetSession", "PQC decapsulation failed, classical only: ${e.message}")
            }
        }

        // Combine classical + PQC secrets via HKDF
        val ikm = if (pqcSecret.isNotEmpty()) classicalSecret + pqcSecret else classicalSecret

        val sharedSecret = hkdfSha256(
            ikm = ikm,
            salt = "ChatControll-v1-ratchet-init".toByteArray(),
            info = "hybrid-key-establishment".toByteArray(),
            length = 32,
        )

        // Sort keys so both peers compute the same sessionId regardless of role
        val localHex = localIdentity.publicIdentityKey.toHex()
        val remoteHex = remotePublicBundle.publicIdentityKey.toHex()
        val orderedKeys = if (localHex < remoteHex) {
            localIdentity.publicIdentityKey + remotePublicBundle.publicIdentityKey
        } else {
            remotePublicBundle.publicIdentityKey + localIdentity.publicIdentityKey
        }
        val sessionId = sha256Hex(orderedKeys)

        // Derive two directional chain keys so both sides can send immediately.
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
            pendingKemCiphertext = kemCiphertext,
            pqcEstablished = pqcSecret.isNotEmpty(),
        )

        sessionsMutex.withLock {
            sessions[sessionId] = state
            persistSession(sessionId, state)
        }

        return SessionKeys(
            sendKey = if (isInitiator) chainA else chainB,
            receiveKey = if (isInitiator) chainB else chainA,
            sessionId = sessionId,
            pqcEstablished = pqcSecret.isNotEmpty(),
        )
    }

    override suspend fun encrypt(sessionKeys: SessionKeys, plaintext: ByteArray): EncryptedEnvelope {
        return sessionsMutex.withLock {
            val state = getOrLoadSession(sessionKeys.sessionId)
                ?: throw IllegalStateException("No ratchet session for ${sessionKeys.sessionId}")

            val (header, ciphertext) = ratchet.encrypt(state, plaintext)

            // Attach PQC KEM ciphertext to the first outbound message header
            val finalHeader = if (state.pendingKemCiphertext != null) {
                val ct = state.pendingKemCiphertext!!
                state.pendingKemCiphertext = null
                header.copy(kemCiphertext = Base64.encodeToString(ct, Base64.NO_WRAP))
            } else {
                header
            }

            val headerJson = json.encodeToString(finalHeader)

            persistSession(sessionKeys.sessionId, state)

            EncryptedEnvelope(
                ciphertext = ciphertext,
                nonce = headerJson.toByteArray(Charsets.UTF_8),
                ephemeralPublicKey = Base64.decode(finalHeader.publicKey, Base64.NO_WRAP),
            )
        }
    }

    override suspend fun decrypt(sessionKeys: SessionKeys, envelope: EncryptedEnvelope): ByteArray {
        return sessionsMutex.withLock {
            val state = getOrLoadSession(sessionKeys.sessionId)
                ?: throw IllegalStateException("No ratchet session for ${sessionKeys.sessionId}")

            val headerJson = String(envelope.nonce, Charsets.UTF_8)
            val header = json.decodeFromString<RatchetHeader>(headerJson)

            try {
                val plaintext = ratchet.decrypt(state, header, envelope.ciphertext)
                persistSession(sessionKeys.sessionId, state)
                plaintext
            } catch (e: Exception) {
                // ratchet.decrypt() mutates state in-place (DH ratchet step, skip keys)
                // before AES-GCM decryption. If decryption fails, the in-memory state
                // is now out of sync. Reload from persisted state to undo the damage.
                val restored = loadPersistedSession(sessionKeys.sessionId)
                if (restored != null) {
                    sessions[sessionKeys.sessionId] = restored
                } else {
                    sessions.remove(sessionKeys.sessionId)
                }
                throw e
            }
        }
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

    // ── Session persistence ──────────────────────────────────────────

    private fun persistSession(sessionId: String, state: RatchetState) {
        val dto = SerializableRatchetState(
            dhPublicKey = state.dhKeyPair.publicKey.b64(),
            dhPrivateKey = state.dhKeyPair.privateKey.b64(),
            remoteDhPublicKey = state.remoteDhPublicKey?.b64(),
            rootKey = state.rootKey.b64(),
            sendingChainKey = state.sendingChainKey?.key?.b64(),
            sendingChainIndex = state.sendingChainKey?.index ?: 0,
            receivingChainKey = state.receivingChainKey?.key?.b64(),
            receivingChainIndex = state.receivingChainKey?.index ?: 0,
            previousSendingChainLength = state.previousSendingChainLength,
            skippedKeys = state.skippedMessageKeys.map { (k, v) ->
                SkippedKeyEntry(k.first, k.second, v.b64())
            },
            pendingKemCiphertext = state.pendingKemCiphertext?.b64(),
            pqcEstablished = state.pqcEstablished,
        )
        keyManager.saveRatchetState(sessionId, json.encodeToString(dto))
    }

    private fun loadPersistedSession(sessionId: String): RatchetState? {
        val serialized = keyManager.loadRatchetState(sessionId) ?: return null
        return try {
            val dto = json.decodeFromString<SerializableRatchetState>(serialized)
            RatchetState(
                dhKeyPair = DhKeyPair(dto.dhPublicKey.fromB64(), dto.dhPrivateKey.fromB64()),
                remoteDhPublicKey = dto.remoteDhPublicKey?.fromB64(),
                rootKey = dto.rootKey.fromB64(),
                sendingChainKey = dto.sendingChainKey?.let { ChainKey(it.fromB64(), dto.sendingChainIndex) },
                receivingChainKey = dto.receivingChainKey?.let { ChainKey(it.fromB64(), dto.receivingChainIndex) },
                previousSendingChainLength = dto.previousSendingChainLength,
                skippedMessageKeys = dto.skippedKeys.associate {
                    (it.publicKeyHex to it.messageNumber) to it.key.fromB64()
                }.toMutableMap(),
                pendingKemCiphertext = dto.pendingKemCiphertext?.fromB64(),
                pqcEstablished = dto.pqcEstablished,
            )
        } catch (e: Exception) {
            Log.w("RatchetSession", "Failed to load session $sessionId: ${e.message}")
            keyManager.removeRatchetState(sessionId)
            null
        }
    }

    private fun getOrLoadSession(sessionId: String): RatchetState? {
        sessions[sessionId]?.let { return it }
        val loaded = loadPersistedSession(sessionId) ?: return null
        sessions[sessionId] = loaded
        return loaded
    }
}

@Serializable
private data class SerializableRatchetState(
    val dhPublicKey: String,
    val dhPrivateKey: String,
    val remoteDhPublicKey: String?,
    val rootKey: String,
    val sendingChainKey: String?,
    val sendingChainIndex: Int,
    val receivingChainKey: String?,
    val receivingChainIndex: Int,
    val previousSendingChainLength: Int,
    val skippedKeys: List<SkippedKeyEntry>,
    val pendingKemCiphertext: String?,
    val pqcEstablished: Boolean,
)

@Serializable
private data class SkippedKeyEntry(
    val publicKeyHex: String,
    val messageNumber: Int,
    val key: String,
)

private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

private fun sha256Hex(data: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

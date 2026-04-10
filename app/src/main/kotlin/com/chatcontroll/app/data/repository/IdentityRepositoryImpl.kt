package com.chatcontroll.app.data.repository

import android.util.Base64
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.SessionResetSender
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.data.local.entity.ContactEntity
import com.chatcontroll.app.data.local.entity.MessageEntity
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.dto.BootstrapRequest
import com.chatcontroll.app.data.remote.dto.KeyRotationRequest
import com.chatcontroll.app.domain.model.Contact
import com.chatcontroll.app.domain.model.Identity
import com.chatcontroll.app.domain.model.MessageState
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.domain.model.KeyType
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val pqcProvider: PqcProvider,
    private val keyManager: KeyManager,
    private val apiService: ApiService,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val sessionResetSender: SessionResetSender,
) : IdentityRepository {

    override suspend fun hasIdentity(): Boolean = keyManager.hasIdentity()

    /**
     * Crash recovery: if staged keys exist AND the server confirmed the
     * rotation, the app crashed before local promotion. Promote the staged
     * keys and invalidate all session state so peers re-establish.
     *
     * If staged keys exist but the server never confirmed (crash between
     * staging and the server call), discard them — the server still has
     * the old keys, and promoting would cause an auth mismatch.
     *
     * This is intentionally separate from [getIdentity] to avoid surprising
     * side effects (key promotion + session clearing) inside a getter. Call
     * this once during app initialization or before the first identity access.
     */
    suspend fun recoverFromInterruptedKeyRotation() {
        if (!keyManager.hasStagedKeys()) return
        if (keyManager.isRotationConfirmedByServer()) {
            keyManager.promoteStagedKeys()
            keyManager.clearSessionCache()
            (cryptoEngine as? com.chatcontroll.app.crypto.ratchet.RatchetSessionManager)
                ?.clearAllSessions()
        } else {
            keyManager.clearStagedKeys()
        }
    }

    override suspend fun getIdentity(): Identity? {
        val keyPair = keyManager.loadIdentityKeyPair() ?: return null
        val userId = keyManager.getUserId() ?: return null
        val shareCode = keyManager.getShareCode()
            ?: cryptoEngine.deriveShareCode(keyPair.publicIdentityKey)
        val createdAtMs = keyManager.getCreatedAt()
        val createdAt = if (createdAtMs > 0) {
            Instant.fromEpochMilliseconds(createdAtMs)
        } else {
            // Legacy identity without stored timestamp — backfill with now
            val now = Clock.System.now()
            keyManager.storeCreatedAt(now.toEpochMilliseconds())
            now
        }
        return Identity(
            userId = userId,
            publicSigningKey = keyPair.publicSigningKey,
            publicIdentityKey = keyPair.publicIdentityKey,
            createdAt = createdAt,
            shareCode = shareCode,
        )
    }

    override suspend fun getOrCreateIdentity(keyType: KeyType): Identity {
        getIdentity()?.let { return it }

        // If keys exist but userId is missing, a previous bootstrap partially
        // failed. Regenerate to avoid duplicate/ambiguous server registrations.
        val keyPair = run {
            val kp = cryptoEngine.generateIdentity()
            keyManager.storeIdentityKeyPair(kp)
            kp
        }

        // Generate PQC keys only if the user chose hybrid post-quantum.
        // If the user explicitly chose PQC, key generation MUST succeed —
        // silent fallback to classical would be a cryptographic downgrade.
        // Always regenerate PQC keys alongside classical keys to keep the
        // key bundle consistent — reusing orphaned PQC keys with new classical
        // keys would produce a mismatched bundle.
        var pqcEk = ByteArray(0)
        var pqcSigningKey = ByteArray(0)
        if (keyType == KeyType.HYBRID_POST_QUANTUM) {
            val kemKeyPair = pqcProvider.generateKemKeyPair()
            keyManager.storePqcKeys(kemKeyPair.encapsulationKey, kemKeyPair.decapsulationKey)
            pqcEk = kemKeyPair.encapsulationKey
            kemKeyPair.decapsulationKey.fill(0)

            val dsaKeyPair = pqcProvider.generateSigningKeyPair()
            keyManager.storeMlDsaKeys(dsaKeyPair.publicKey, dsaKeyPair.privateKey)
            pqcSigningKey = dsaKeyPair.publicKey
            dsaKeyPair.privateKey.fill(0)
        }

        // Register with relay server
        val response = apiService.bootstrapIdentity(
            BootstrapRequest(
                publicSigningKey = Base64.encodeToString(keyPair.publicSigningKey, Base64.NO_WRAP),
                publicIdentityKey = Base64.encodeToString(keyPair.publicIdentityKey, Base64.NO_WRAP),
                pqcEncapsulationKey = if (pqcEk.isNotEmpty()) Base64.encodeToString(pqcEk, Base64.NO_WRAP) else "",
                pqcSigningKey = if (pqcSigningKey.isNotEmpty()) Base64.encodeToString(pqcSigningKey, Base64.NO_WRAP) else "",
            )
        )

        val now = Clock.System.now()
        keyManager.storeUserId(response.userId)
        keyManager.storeShareCode(response.shareCode)
        keyManager.storeCreatedAt(now.toEpochMilliseconds())

        return Identity(
            userId = response.userId,
            publicSigningKey = keyPair.publicSigningKey,
            publicIdentityKey = keyPair.publicIdentityKey,
            createdAt = now,
            shareCode = response.shareCode,
        )
    }

    override suspend fun addContact(shareCode: String): Contact {
        val resolved = apiService.resolveShareCode(shareCode)
            ?: throw IllegalArgumentException("Unknown share code")

        val pubIdKey = try {
            Base64.decode(resolved.publicIdentityKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed Base64 in identity key for ${resolved.userId.take(8)}", e)
        }
        val pubSignKey = try {
            Base64.decode(resolved.publicSigningKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed Base64 in signing key for ${resolved.userId.take(8)}", e)
        }

        val pqcSignKey = if (resolved.pqcSigningKey.isNotEmpty()) {
            try {
                Base64.decode(resolved.pqcSigningKey, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                ByteArray(0)
            }
        } else ByteArray(0)

        val contact = Contact(
            userId = resolved.userId,
            displayName = resolved.userId.take(8),
            publicIdentityKey = pubIdKey,
            publicSigningKey = pubSignKey,
            pqcSigningKey = pqcSignKey,
        )

        // Establish crypto session BEFORE persisting the contact — if session
        // establishment fails, we don't leave an orphaned contact in the DB
        // that shows up in the contacts list without a working session.
        val localKeyPair = keyManager.loadIdentityKeyPair()
            ?: throw IllegalStateException("No local identity")

        val pqcKey = if (resolved.pqcEncapsulationKey.isNotEmpty()) {
            try {
                Base64.decode(resolved.pqcEncapsulationKey, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Malformed Base64 in PQC key for ${resolved.userId.take(8)}", e)
            }
        } else {
            ByteArray(0)
        }

        val sessionKeys = cryptoEngine.establishSession(
            localIdentity = localKeyPair,
            remotePublicBundle = PublicKeyBundle(
                publicSigningKey = pubSignKey,
                publicIdentityKey = pubIdKey,
                pqcEncapsulationKey = pqcKey,
            ),
        )
        keyManager.cacheSessionKeys(contact.userId, sessionKeys)

        // Persist contact only after session is successfully established
        contactDao.upsert(
            ContactEntity(
                userId = contact.userId,
                displayName = contact.displayName,
                publicIdentityKey = contact.publicIdentityKey,
                publicSigningKey = contact.publicSigningKey,
                pqcSigningKey = contact.pqcSigningKey,
                pqcEstablished = sessionKeys.pqcEstablished,
            )
        )

        return contact
    }

    override fun getContacts(): Flow<List<Contact>> {
        return contactDao.getAll().map { entities ->
            entities.map { entity ->
                Contact(
                    userId = entity.userId,
                    displayName = entity.displayName,
                    publicIdentityKey = entity.publicIdentityKey,
                    publicSigningKey = entity.publicSigningKey,
                    pqcSigningKey = entity.pqcSigningKey,
                    verified = entity.verified,
                )
            }
        }
    }

    override suspend fun getContact(userId: String): Contact? {
        val entity = contactDao.getByUserId(userId) ?: return null
        return Contact(
            userId = entity.userId,
            displayName = entity.displayName,
            publicIdentityKey = entity.publicIdentityKey,
            publicSigningKey = entity.publicSigningKey,
            pqcSigningKey = entity.pqcSigningKey,
            verified = entity.verified,
        )
    }

    override suspend fun rotateIdentityKeys() {
        // Generate fresh identity keys
        val newKeyPair = cryptoEngine.generateIdentity()

        // Generate fresh PQC keys if current identity has them.
        // If the user has PQC keys, regeneration MUST succeed — partial rotation
        // (new classical + stale PQC) would mix key epochs.
        val currentPqcEk = keyManager.getPqcEncapsulationKey()
        val hasPqc = currentPqcEk != null && currentPqcEk.isNotEmpty()
        val newPqcEk = if (hasPqc) {
            pqcProvider.generateKemKeyPair()
        } else null
        val currentMlDsa = keyManager.getMlDsaPublicKey()
        val hasMlDsa = currentMlDsa != null && currentMlDsa.isNotEmpty()
        val newMlDsa = if (hasMlDsa) {
            pqcProvider.generateSigningKeyPair()
        } else null

        // Base64-encode the new public keys
        val newSignB64 = Base64.encodeToString(newKeyPair.publicSigningKey, Base64.NO_WRAP)
        val newIdB64 = Base64.encodeToString(newKeyPair.publicIdentityKey, Base64.NO_WRAP)
        val newPqcB64 = newPqcEk?.let {
            Base64.encodeToString(it.encapsulationKey, Base64.NO_WRAP)
        }
        var newMlDsaB64 = newMlDsa?.let {
            Base64.encodeToString(it.publicKey, Base64.NO_WRAP)
        }

        // Proof of possession: sign the new public_signing_key B64 string
        // with the NEW private signing key (server verifies with new public key)
        val proofData = newSignB64.toByteArray(Charsets.UTF_8)
        val kf = java.security.KeyFactory.getInstance("Ed25519", "BC")
        val newPrivKey = kf.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(newKeyPair.privateSigningKey)
        )
        val sig = java.security.Signature.getInstance("Ed25519", "BC")
        sig.initSign(newPrivKey)
        sig.update(proofData)
        val proofB64 = Base64.encodeToString(sig.sign(), Base64.NO_WRAP)

        // Stage new keys locally BEFORE the server call so that if the server
        // accepts but the app crashes before local promotion, the next launch
        // can recover by promoting the staged keys.
        keyManager.stageIdentityKeyPair(newKeyPair)
        if (newPqcEk != null) {
            keyManager.stagePqcKeys(newPqcEk.encapsulationKey, newPqcEk.decapsulationKey)
        }
        if (newMlDsa != null) {
            keyManager.stageMlDsaKeys(newMlDsa.publicKey, newMlDsa.privateKey)
        }
        // ML-DSA proof-of-possession: sign the new pqc_signing_key B64 with the new ML-DSA private key.
        // If proof generation fails, clear the new key so neither is sent — sending a key
        // without proof would be rejected by the server, and sending an empty proof
        // could silently register an unverified key.
        var pqcProofB64 = ""
        if (newMlDsa != null && newMlDsaB64 != null) {
            try {
                pqcProofB64 = Base64.encodeToString(
                    pqcProvider.sign(newMlDsaB64.toByteArray(Charsets.UTF_8), newMlDsa.privateKey),
                    Base64.NO_WRAP,
                )
            } catch (e: Exception) {
                android.util.Log.e("IdentityRepo", "ML-DSA proof-of-possession failed, omitting PQC key", e)
                newMlDsaB64 = null
            }
        }

        // Zeroize PQC private keys now that staging and proof signing are done
        newPqcEk?.decapsulationKey?.fill(0)
        newMlDsa?.privateKey?.fill(0)

        // Call server (authenticated with the CURRENT signing key via authToken)
        val response = try {
            apiService.rotateKeys(
                KeyRotationRequest(
                    publicSigningKey = newSignB64,
                    publicIdentityKey = newIdB64,
                    pqcEncapsulationKey = newPqcB64,
                    pqcSigningKey = newMlDsaB64,
                    newKeyProof = proofB64,
                    pqcKeyProof = pqcProofB64,
                )
            )
        } catch (e: Exception) {
            keyManager.clearStagedKeys()
            throw e
        }

        // Server accepted — mark confirmed (persisted synchronously) so crash
        // recovery knows the server has the new keys, then promote.
        keyManager.markRotationConfirmedByServer()
        keyManager.promoteStagedKeys()
        keyManager.storeShareCode(response.shareCode)

        // Invalidate all session caches — peers will re-establish on next message
        keyManager.clearSessionCache()
        (cryptoEngine as? com.chatcontroll.app.crypto.ratchet.RatchetSessionManager)
            ?.clearAllSessions()

        // Record key-change events and notify all contacts.
        // Best-effort — don't fail rotation if a notification can't be delivered.
        val localUserId = keyManager.getUserId() ?: ""
        val now = Clock.System.now().toEpochMilliseconds()
        try {
            val contacts = contactDao.getAll().first()
            for (contact in contacts) {
                // Insert a key-change event into each conversation
                val conversation = conversationDao.getByContactId(contact.userId)
                if (conversation != null) {
                    messageDao.insert(MessageEntity(
                        id = "keychange-local-${contact.userId}-$now",
                        conversationId = conversation.id,
                        senderId = localUserId,
                        recipientId = contact.userId,
                        encryptedBody = ByteArray(0),
                        nonce = ByteArray(0),
                        plaintext = "",
                        state = MessageState.KEY_ROTATED_LOCAL.name,
                        timestamp = now,
                        expiresAt = null,
                        isOutgoing = true,
                    ))
                }

                try {
                    sessionResetSender.send(contact.userId)
                } catch (e: Exception) {
                    android.util.Log.w("IdentityRepo",
                        "Failed to send session_reset to ${contact.userId.take(8)}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("IdentityRepo",
                "Failed to notify contacts of key rotation: ${e.message}")
        }
    }

    override suspend fun fetchKeyBundle(userId: String): Contact? {
        val bundle = apiService.fetchKeyBundle(userId) ?: return null
        val pubIdKey = try {
            Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed Base64 in identity key bundle for ${userId.take(8)}", e)
        }
        val pubSignKey = try {
            Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed Base64 in signing key bundle for ${userId.take(8)}", e)
        }
        val pqcSignKey = if (bundle.pqcSigningKey.isNotEmpty()) {
            try {
                Base64.decode(bundle.pqcSigningKey, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                ByteArray(0)
            }
        } else ByteArray(0)
        return Contact(
            userId = bundle.userId,
            displayName = bundle.userId.take(8),
            publicIdentityKey = pubIdKey,
            publicSigningKey = pubSignKey,
            pqcSigningKey = pqcSignKey,
        )
    }

    override fun isPqcSession(peerId: String): Boolean = keyManager.isPeerPqcEstablished(peerId)

    override fun observePqcSession(peerId: String): Flow<Boolean> {
        return contactDao.observeByUserId(peerId).map { it?.pqcEstablished == true }
    }
}

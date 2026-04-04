package com.chatcontroll.app.data.repository

import android.util.Base64
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.entity.ContactEntity
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.dto.BootstrapRequest
import com.chatcontroll.app.data.remote.dto.KeyRotationRequest
import com.chatcontroll.app.domain.model.Contact
import com.chatcontroll.app.domain.model.Identity
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.domain.model.KeyType
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import kotlinx.coroutines.flow.Flow
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
) : IdentityRepository {

    override suspend fun hasIdentity(): Boolean = keyManager.hasIdentity()

    override suspend fun getIdentity(): Identity? {
        // Crash recovery: if staged keys exist, the server accepted the rotation
        // but the app crashed before promotion. Promote them now.
        if (keyManager.hasStagedKeys()) {
            keyManager.promoteStagedKeys()
            keyManager.clearSessionCache()
            (cryptoEngine as? com.chatcontroll.app.crypto.ratchet.RatchetSessionManager)
                ?.clearAllSessions()
        }
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

        // Reuse keys from a previous failed attempt, or generate new ones
        val keyPair = keyManager.loadIdentityKeyPair() ?: run {
            val kp = cryptoEngine.generateIdentity()
            keyManager.storeIdentityKeyPair(kp)
            kp
        }

        // Generate PQC keys only if the user chose hybrid post-quantum
        val pqcEk = if (keyType == KeyType.HYBRID_POST_QUANTUM) {
            keyManager.getPqcEncapsulationKey() ?: run {
                try {
                    val kemKeyPair = pqcProvider.generateKemKeyPair()
                    keyManager.storePqcKeys(kemKeyPair.encapsulationKey, kemKeyPair.decapsulationKey)
                    kemKeyPair.encapsulationKey
                } catch (e: Exception) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("Identity", "PQC key gen failed, falling back to classical: ${e.message}")
                    ByteArray(0)
                }
            }
        } else {
            ByteArray(0)
        }

        // Register with relay server
        val response = apiService.bootstrapIdentity(
            BootstrapRequest(
                publicSigningKey = Base64.encodeToString(keyPair.publicSigningKey, Base64.NO_WRAP),
                publicIdentityKey = Base64.encodeToString(keyPair.publicIdentityKey, Base64.NO_WRAP),
                pqcEncapsulationKey = if (pqcEk.isNotEmpty()) Base64.encodeToString(pqcEk, Base64.NO_WRAP) else "",
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

        val pubIdKey = Base64.decode(resolved.publicIdentityKey, Base64.NO_WRAP)
        val pubSignKey = Base64.decode(resolved.publicSigningKey, Base64.NO_WRAP)

        val contact = Contact(
            userId = resolved.userId,
            displayName = resolved.userId.take(8),
            publicIdentityKey = pubIdKey,
            publicSigningKey = pubSignKey,
        )

        // Store contact locally
        contactDao.upsert(
            ContactEntity(
                userId = contact.userId,
                displayName = contact.displayName,
                publicIdentityKey = contact.publicIdentityKey,
                publicSigningKey = contact.publicSigningKey,
            )
        )

        // Establish crypto session
        val localKeyPair = keyManager.loadIdentityKeyPair()
            ?: throw IllegalStateException("No local identity")

        val pqcKey = if (resolved.pqcEncapsulationKey.isNotEmpty()) {
            Base64.decode(resolved.pqcEncapsulationKey, Base64.NO_WRAP)
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

        // Base64-encode the new public keys
        val newSignB64 = Base64.encodeToString(newKeyPair.publicSigningKey, Base64.NO_WRAP)
        val newIdB64 = Base64.encodeToString(newKeyPair.publicIdentityKey, Base64.NO_WRAP)
        val newPqcB64 = newPqcEk?.let {
            Base64.encodeToString(it.encapsulationKey, Base64.NO_WRAP)
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

        // Call server (authenticated with the CURRENT signing key via authToken)
        val response = try {
            apiService.rotateKeys(
                KeyRotationRequest(
                    publicSigningKey = newSignB64,
                    publicIdentityKey = newIdB64,
                    pqcEncapsulationKey = newPqcB64,
                    newKeyProof = proofB64,
                )
            )
        } catch (e: Exception) {
            keyManager.clearStagedKeys()
            throw e
        }

        // Server accepted — promote staged keys to active
        keyManager.promoteStagedKeys()
        keyManager.storeShareCode(response.shareCode)

        // Invalidate all session caches — peers will re-establish on next message
        keyManager.clearSessionCache()
        (cryptoEngine as? com.chatcontroll.app.crypto.ratchet.RatchetSessionManager)
            ?.clearAllSessions()
    }

    override suspend fun fetchKeyBundle(userId: String): Contact? {
        val bundle = apiService.fetchKeyBundle(userId) ?: return null
        return Contact(
            userId = bundle.userId,
            displayName = bundle.userId.take(8),
            publicIdentityKey = Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP),
            publicSigningKey = Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP),
        )
    }

    override fun isPqcSession(peerId: String): Boolean = keyManager.isPeerPqcEstablished(peerId)
}

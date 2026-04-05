package com.chatcontroll.app.data.repository

import android.util.Base64
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.data.local.entity.ContactEntity
import com.chatcontroll.app.data.local.entity.MessageEntity
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.dto.AckRequest
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import com.chatcontroll.app.domain.model.Message
import com.chatcontroll.app.domain.model.MessageState
import com.chatcontroll.app.crypto.ratchet.RatchetHeader
import com.chatcontroll.app.domain.repository.CryptoEngine
import com.chatcontroll.app.domain.repository.EncryptedEnvelope
import com.chatcontroll.app.domain.repository.MessageRepository
import com.chatcontroll.app.domain.repository.PublicKeyBundle
import com.chatcontroll.app.domain.repository.SessionKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val apiService: ApiService,
    private val cryptoEngine: CryptoEngine,
    private val keyManager: KeyManager,
) : MessageRepository {

    private val fetchLock = kotlinx.coroutines.sync.Mutex()
    private val peerLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val headerJson = Json { ignoreUnknownKeys = true }

    /** Track consecutive decrypt failures per message to avoid infinite retry. */
    private val decryptFailCounts = mutableMapOf<String, Int>()

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain(keyManager.getUserId() ?: "") }
        }
    }

    override suspend fun sendMessage(
        conversationId: String,
        recipientId: String,
        plaintext: String,
    ): Message {
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val senderId = keyManager.getUserId() ?: throw IllegalStateException("No identity")

        // Serialize ratchet operations per peer to prevent state divergence
        val peerMutex = peerLocks.getOrPut(recipientId) { kotlinx.coroutines.sync.Mutex() }
        val envelope = peerMutex.withLock {
            var sessionKeys = keyManager.getCachedSessionKeys(recipientId)
            if (sessionKeys == null) {
                sessionKeys = tryEstablishSession(recipientId)
                    ?: throw IllegalStateException("No session established with $recipientId")
            }
            cryptoEngine.encrypt(sessionKeys, plaintext.toByteArray(Charsets.UTF_8))
        }

        // Store locally as SENDING
        val entity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            encryptedBody = envelope.ciphertext,
            nonce = envelope.nonce,
            plaintext = plaintext,
            state = MessageState.SENDING.name,
            timestamp = now,
            expiresAt = null,
            isOutgoing = true,
        )
        messageDao.insert(entity)

        // Sign the envelope for recipient verification (length-prefixed to prevent ambiguity)
        val sigPayload = lengthPrefixed(senderId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(recipientId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(envelope.nonce) + envelope.ciphertext
        val signature = keyManager.sign(sigPayload)
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        // Send to server
        try {
            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                    signature = signatureB64,
                )
            )
            messageDao.updateStateAndTimestamp(messageId, MessageState.SENT.name, response.timestamp)

            updateConversationPreview(conversationId, recipientId, plaintext, response.timestamp)

            return entity.toDomain(senderId).copy(
                state = MessageState.SENT,
                plaintext = plaintext,
            )
        } catch (e: Exception) {
            messageDao.updateState(messageId, MessageState.FAILED.name)
            throw e
        }
    }

    override suspend fun markDelivered(messageId: String) {
        messageDao.updateState(messageId, MessageState.DELIVERED.name)
    }

    override suspend fun markSeen(messageId: String) {
        messageDao.updateState(messageId, MessageState.SEEN.name)
    }

    override suspend fun retryFailed(messageId: String) {
        val entity = messageDao.getById(messageId) ?: return
        val plaintext = entity.plaintext ?: return // Cannot retry without plaintext
        messageDao.updateState(messageId, MessageState.SENDING.name)

        try {
            // Re-encrypt with current ratchet state instead of sending stale ciphertext
            var sessionKeys = keyManager.getCachedSessionKeys(entity.recipientId)
            if (sessionKeys == null) {
                sessionKeys = tryEstablishSession(entity.recipientId)
                    ?: throw IllegalStateException("No session for retry")
            }

            val envelope = cryptoEngine.encrypt(sessionKeys, plaintext.toByteArray(Charsets.UTF_8))

            val retrySenderId = keyManager.getUserId() ?: throw IllegalStateException("No identity")
            val retrySigPayload = lengthPrefixed(retrySenderId.toByteArray(Charsets.UTF_8)) +
                lengthPrefixed(entity.recipientId.toByteArray(Charsets.UTF_8)) +
                lengthPrefixed(envelope.nonce) + envelope.ciphertext
            val retrySignature = keyManager.sign(retrySigPayload)

            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = entity.recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                    signature = Base64.encodeToString(retrySignature, Base64.NO_WRAP),
                )
            )
            messageDao.updateStateAndTimestamp(messageId, MessageState.SENT.name, response.timestamp)
        } catch (e: Exception) {
            messageDao.updateState(messageId, MessageState.FAILED.name)
            throw e
        }
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.delete(messageId)
    }

    override suspend fun deleteMessagesOlderThan(conversationId: String, timestampMillis: Long) {
        messageDao.deleteOlderThan(conversationId, timestampMillis)
    }

    override suspend fun fetchPendingFromServer() {
        if (!fetchLock.tryLock()) return // skip if already fetching
        try {
            fetchPendingInternal()
        } finally {
            fetchLock.unlock()
        }
    }

    private suspend fun fetchPendingInternal() {
        val pending = apiService.fetchPendingMessages()
        if (pending.isEmpty()) return

        val localUserId = keyManager.getUserId() ?: return

        val receivedIds = mutableListOf<String>()

        for (dto in pending) {
            // Extract KEM ciphertext from message header for PQC handshake
            val kemCiphertext = try {
                val nonce = Base64.decode(dto.nonce, Base64.NO_WRAP)
                val header = headerJson.decodeFromString<RatchetHeader>(String(nonce, Charsets.UTF_8))
                header.kemCiphertext?.let { Base64.decode(it, Base64.NO_WRAP) }
            } catch (_: Exception) { null }

            // Auto-establish session, or re-establish if sender upgraded to PQC
            var sessionKeys = keyManager.getCachedSessionKeys(dto.senderId)
            if (sessionKeys == null || (kemCiphertext != null && !sessionKeys.pqcEstablished)) {
                sessionKeys = tryEstablishSession(dto.senderId, kemCiphertext)
            }
            if (sessionKeys == null) continue

            val envelope = EncryptedEnvelope(
                ciphertext = Base64.decode(dto.encryptedBody, Base64.NO_WRAP),
                nonce = Base64.decode(dto.nonce, Base64.NO_WRAP),
            )

            // Check if signature is required: reject unsigned messages unless
            // the contact explicitly has signatureRequired=false (legacy contact)
            val senderContact = contactDao.getByUserId(dto.senderId)
            if (dto.signature.isEmpty() && (senderContact == null || senderContact.signatureRequired)) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Rejecting unsigned message from ${dto.senderId.take(8)}")
                storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                receivedIds.add(dto.messageId)
                continue
            }

            // Verify sender signature if present
            if (dto.signature.isNotEmpty()) {
                // Ensure contact exists so we have the public signing key
                var contact = contactDao.getByUserId(dto.senderId)
                if (contact == null) {
                    // Force session establishment to fetch and save the key bundle
                    tryEstablishSession(dto.senderId, kemCiphertext)
                    contact = contactDao.getByUserId(dto.senderId)
                }
                if (contact == null) {
                    // Cannot verify — reject the message
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Cannot verify signature: unknown sender ${dto.messageId}")
                    storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                    continue
                }
                val sigPayload = lengthPrefixed(dto.senderId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(localUserId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(envelope.nonce) + envelope.ciphertext
                val sig = Base64.decode(dto.signature, Base64.NO_WRAP)
                val valid = cryptoEngine.verify(sigPayload, sig, contact.publicSigningKey)
                if (!valid) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Signature verification failed for ${dto.messageId}")
                    // Don't ACK immediately — leave in pending queue for retry on
                    // next sync (key rotation race could cause transient failure).
                    // ACK after MAX_DECRYPT_RETRIES to prevent queue poisoning.
                    countDecryptFailure(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp, receivedIds)
                    continue
                }
            }

            val peerMutex = peerLocks.getOrPut(dto.senderId) { kotlinx.coroutines.sync.Mutex() }
            val plaintext = try {
                val result = peerMutex.withLock {
                    cryptoEngine.decrypt(sessionKeys, envelope)
                }
                decryptFailCounts.remove(dto.messageId)
                result
            } catch (e: Exception) {
                // If both sides encapsulated independently (different PQC secrets),
                // re-establish the session using the inbound KEM ciphertext.
                if (kemCiphertext != null && sessionKeys.pqcEstablished) {
                    val reEstablished = tryEstablishSession(dto.senderId, kemCiphertext)
                    if (reEstablished != null) {
                        try {
                            val result = peerMutex.withLock {
                                cryptoEngine.decrypt(reEstablished, envelope)
                            }
                            decryptFailCounts.remove(dto.messageId)
                            result
                        } catch (retryEx: Exception) {
                            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo",
                                "Decrypt failed after PQC re-establish from ${dto.senderId}: ${retryEx.message}", retryEx)
                            countDecryptFailure(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp, receivedIds)
                            continue
                        }
                    } else {
                        if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo", "Decrypt failed from ${dto.senderId}: ${e.message}", e)
                        countDecryptFailure(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp, receivedIds)
                        continue
                    }
                } else {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo", "Decrypt failed from ${dto.senderId}: ${e.message}", e)
                    countDecryptFailure(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp, receivedIds)
                    continue
                }
            }

            val conversationId = getOrCreateConversationId(dto.senderId)

            val plaintextStr = String(plaintext, Charsets.UTF_8)

            val entity = MessageEntity(
                id = dto.messageId,
                conversationId = conversationId,
                senderId = dto.senderId,
                recipientId = localUserId,
                encryptedBody = envelope.ciphertext,
                nonce = envelope.nonce,
                plaintext = plaintextStr,
                state = MessageState.DELIVERED.name,
                timestamp = dto.timestamp,
                expiresAt = null,
                isOutgoing = false,
            )
            messageDao.insert(entity)
            receivedIds.add(dto.messageId)

            updateConversationPreview(
                conversationId,
                dto.senderId,
                plaintextStr,
                dto.timestamp,
                incrementUnread = true,
            )
        }

        if (receivedIds.isNotEmpty()) {
            apiService.acknowledgeMessages(AckRequest(receivedIds))
        }
    }

    /**
     * Fetch a sender's key bundle from the server and establish a crypto session.
     * Also saves them as a contact so future messages work.
     */
    private suspend fun tryEstablishSession(
        remoteUserId: String,
        inboundKemCiphertext: ByteArray? = null,
    ): SessionKeys? {
        return try {
            val bundle = apiService.fetchKeyBundle(remoteUserId) ?: return null
            val localKeyPair = keyManager.loadIdentityKeyPair() ?: return null

            val pubIdKey = try {
                Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo", "Malformed Base64 in identity key for ${remoteUserId.take(8)}", e)
                return null
            }
            val pubSignKey = try {
                Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo", "Malformed Base64 in signing key for ${remoteUserId.take(8)}", e)
                return null
            }
            val pqcKey = if (bundle.pqcEncapsulationKey.isNotEmpty()) {
                try {
                    Base64.decode(bundle.pqcEncapsulationKey, Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo", "Malformed Base64 in PQC key for ${remoteUserId.take(8)}", e)
                    return null
                }
            } else ByteArray(0)

            // Only pass PQC key when we have inbound KEM ciphertext to decapsulate.
            // Without it, we would encapsulate and derive a hybrid root key that
            // doesn't match the sender's existing session.
            val sessionKeys = cryptoEngine.establishSession(
                localIdentity = localKeyPair,
                remotePublicBundle = PublicKeyBundle(
                    publicSigningKey = pubSignKey,
                    publicIdentityKey = pubIdKey,
                    pqcEncapsulationKey = if (inboundKemCiphertext != null) pqcKey else ByteArray(0),
                ),
                inboundKemCiphertext = inboundKemCiphertext,
            )
            keyManager.cacheSessionKeys(remoteUserId, sessionKeys)

            // Save as contact and track PQC status + key continuity
            val existingContact = contactDao.getByUserId(remoteUserId)
            if (existingContact == null) {
                contactDao.upsert(ContactEntity(
                    userId = remoteUserId,
                    displayName = remoteUserId.take(8),
                    publicIdentityKey = pubIdKey,
                    publicSigningKey = pubSignKey,
                    pqcEstablished = sessionKeys.pqcEstablished,
                ))
            } else {
                // Key continuity check: reject if signing key changed unexpectedly
                if (!existingContact.publicSigningKey.contentEquals(pubSignKey)) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "KEY CHANGE detected for ${remoteUserId.take(8)}: " +
                        "signing key differs from stored key, rejecting session")
                    return null
                }

                if (existingContact.pqcEstablished && !sessionKeys.pqcEstablished) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "PQC DOWNGRADE REJECTED for ${remoteUserId.take(8)}: " +
                        "contact was hybrid PQ, refusing classical-only session")
                    return null
                } else if (sessionKeys.pqcEstablished && !existingContact.pqcEstablished) {
                    contactDao.upsert(existingContact.copy(pqcEstablished = true))
                }
            }

            sessionKeys
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getOrCreateConversationId(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id

        // New inbound conversation from someone we haven't added — message request
        val contact = contactDao.getByUserId(contactId)
        val displayName = contact?.displayName ?: contactId.take(8)

        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            com.chatcontroll.app.data.local.entity.ConversationEntity(
                id = id,
                contactId = contactId,
                contactDisplayName = displayName,
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = false,
            )
        )
        return id
    }

    private suspend fun countDecryptFailure(
        messageId: String,
        senderId: String,
        localUserId: String,
        envelope: EncryptedEnvelope,
        timestamp: Long,
        receivedIds: MutableList<String>,
    ) {
        val failures = (decryptFailCounts[messageId] ?: 0) + 1
        decryptFailCounts[messageId] = failures
        if (failures >= MAX_DECRYPT_RETRIES) {
            // Permanently failed — store as DECRYPT_FAILED so the user
            // sees a tombstone instead of silently losing the message.
            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Giving up on message $messageId after $failures attempts")
            val conversationId = getOrCreateConversationId(senderId)
            messageDao.insert(MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                recipientId = localUserId,
                encryptedBody = envelope.ciphertext,
                nonce = envelope.nonce,
                plaintext = "",
                state = MessageState.DECRYPT_FAILED.name,
                timestamp = timestamp,
                expiresAt = null,
                isOutgoing = false,
            ))
            receivedIds.add(messageId)
            decryptFailCounts.remove(messageId)
        }
    }

    /** Store a tombstone for a message that was rejected outright (not retriable). */
    private suspend fun storeRejected(
        messageId: String,
        senderId: String,
        localUserId: String,
        envelope: EncryptedEnvelope,
        timestamp: Long,
    ) {
        val conversationId = getOrCreateConversationId(senderId)
        messageDao.insert(MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = localUserId,
            encryptedBody = envelope.ciphertext,
            nonce = envelope.nonce,
            plaintext = "",
            state = MessageState.REJECTED.name,
            timestamp = timestamp,
            expiresAt = null,
            isOutgoing = false,
        ))
    }

    companion object {
        /** After this many failed decrypt attempts, acknowledge the message to
         *  prevent it from poisoning the pending queue forever. */
        private const val MAX_DECRYPT_RETRIES = 3
    }

    private suspend fun updateConversationPreview(
        conversationId: String,
        contactId: String,
        preview: String,
        timestamp: Long,
        incrementUnread: Boolean = false,
    ) {
        val existing = conversationDao.getByContactId(contactId)
        val currentUnread = existing?.unreadCount ?: 0
        conversationDao.upsert(
            com.chatcontroll.app.data.local.entity.ConversationEntity(
                id = conversationId,
                contactId = contactId,
                contactDisplayName = existing?.contactDisplayName ?: contactId.take(8),
                lastMessagePreview = preview.take(100),
                lastMessageTimestamp = timestamp,
                unreadCount = if (incrementUnread) currentUnread + 1 else currentUnread,
                isEncrypted = true,
                isApproved = existing?.isApproved ?: true,
            )
        )
    }
}

/** Prepend 4-byte big-endian length prefix to prevent concatenation ambiguity in signature payloads. */
private fun lengthPrefixed(data: ByteArray): ByteArray {
    return ByteBuffer.allocate(4).putInt(data.size).array() + data
}

private fun MessageEntity.toDomain(localUserId: String): Message {
    return Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        plaintext = plaintext,
        state = MessageState.valueOf(state),
        timestamp = Instant.fromEpochMilliseconds(timestamp),
        expiresAt = expiresAt?.let { Instant.fromEpochMilliseconds(it) },
        isOutgoing = isOutgoing,
    )
}

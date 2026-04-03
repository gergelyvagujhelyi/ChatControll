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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.util.UUID
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
    private val headerJson = Json { ignoreUnknownKeys = true }

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

        // Encrypt the message — re-establish session if not cached
        var sessionKeys = keyManager.getCachedSessionKeys(recipientId)
        if (sessionKeys == null) {
            sessionKeys = tryEstablishSession(recipientId)
                ?: throw IllegalStateException("No session established with $recipientId")
        }

        val envelope = cryptoEngine.encrypt(sessionKeys, plaintext.toByteArray(Charsets.UTF_8))

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

        // Send to server
        try {
            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
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
        messageDao.updateState(messageId, MessageState.SENDING.name)
        // Re-send logic would go through WorkManager in production
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

            // Auto-establish session if we don't have one for this sender
            var sessionKeys = keyManager.getCachedSessionKeys(dto.senderId)
            if (sessionKeys == null) {
                sessionKeys = tryEstablishSession(dto.senderId, kemCiphertext)
            }
            if (sessionKeys == null) continue

            val envelope = EncryptedEnvelope(
                ciphertext = Base64.decode(dto.encryptedBody, Base64.NO_WRAP),
                nonce = Base64.decode(dto.nonce, Base64.NO_WRAP),
            )

            val plaintext = try {
                cryptoEngine.decrypt(sessionKeys, envelope)
            } catch (e: Exception) {
                android.util.Log.e("MessageRepo", "Decrypt failed from ${dto.senderId}: ${e.message}", e)
                continue // Skip messages we can't decrypt
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

            val pubIdKey = Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)
            val pubSignKey = Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP)
            val pqcKey = if (bundle.pqcEncapsulationKey.isNotEmpty()) {
                Base64.decode(bundle.pqcEncapsulationKey, Base64.NO_WRAP)
            } else ByteArray(0)

            val sessionKeys = cryptoEngine.establishSession(
                localIdentity = localKeyPair,
                remotePublicBundle = PublicKeyBundle(
                    publicSigningKey = pubSignKey,
                    publicIdentityKey = pubIdKey,
                    pqcEncapsulationKey = pqcKey,
                ),
                inboundKemCiphertext = inboundKemCiphertext,
            )
            keyManager.cacheSessionKeys(remoteUserId, sessionKeys)

            // Save as contact
            if (contactDao.getByUserId(remoteUserId) == null) {
                contactDao.upsert(ContactEntity(
                    userId = remoteUserId,
                    displayName = remoteUserId.take(8),
                    publicIdentityKey = pubIdKey,
                    publicSigningKey = pubSignKey,
                ))
            }

            sessionKeys
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getOrCreateConversationId(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            com.chatcontroll.app.data.local.entity.ConversationEntity(
                id = id,
                contactId = contactId,
                contactDisplayName = contactId.take(8),
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
            )
        )
        return id
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
            )
        )
    }
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

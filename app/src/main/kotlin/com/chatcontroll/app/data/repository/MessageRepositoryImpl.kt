package com.chatcontroll.app.data.repository

import android.util.Base64
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.PqcProvider
import com.chatcontroll.app.crypto.SessionResetSender
import com.chatcontroll.app.crypto.buildMessageSigPayload
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
import com.chatcontroll.app.notification.ChatNotificationManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
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
    private val pqcProvider: PqcProvider,
    private val sessionResetSender: SessionResetSender,
    private val notificationManager: ChatNotificationManager,
) : MessageRepository {

    private val fetchLock = kotlinx.coroutines.sync.Mutex()
    private val peerLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val headerJson = Json { ignoreUnknownKeys = true }

    /** Track consecutive decrypt failures per message to avoid infinite retry. */
    private val decryptFailCounts = ConcurrentHashMap<String, Int>()

    /** Track skipped syncs for messages missing a PQC signature (may be transient). */
    private val pqcSigMissCounts = ConcurrentHashMap<String, Int>()

    /** Track skipped syncs for control messages missing a PQC signature. */
    private val ctrlPqcSigMissCounts = ConcurrentHashMap<String, Int>()

    /** Track peers already notified with session_reset to avoid duplicate signals. */
    private val sessionResetSentTo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The conversation currently open on screen — skip unread increment for it. */
    @Volatile
    private var activeConversationId: String? = null

    override fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    /**
     * Evict stale tracking entries when maps grow beyond [PRUNE_THRESHOLD]
     * to prevent unbounded memory growth on long-lived installs.
     *
     * peerLocks is intentionally not pruned: its size is bounded by the
     * number of contacts, and removing an "unlocked" mutex races with
     * threads about to acquire it.
     */
    private fun pruneInMemoryMaps() {
        if (decryptFailCounts.size > PRUNE_THRESHOLD) {
            // Evict entries with the lowest retry counts first to ensure that
            // messages nearing the MAX_DECRYPT_RETRIES limit are correctly tombstoned.
            // Snapshot keys to avoid ConcurrentModificationException.
            val evictCount = decryptFailCounts.size - PRUNE_THRESHOLD
            val keysToEvict = decryptFailCounts.entries.toList()
                .sortedBy { it.value }
                .take(evictCount)
                .map { it.key }
            keysToEvict.forEach { decryptFailCounts.remove(it) }
        }
        if (pqcSigMissCounts.size > PRUNE_THRESHOLD) {
            val evictCount = pqcSigMissCounts.size - PRUNE_THRESHOLD
            val keysToEvict = pqcSigMissCounts.entries.toList()
                .sortedBy { it.value }
                .take(evictCount)
                .map { it.key }
            keysToEvict.forEach { pqcSigMissCounts.remove(it) }
        }
        if (sessionResetSentTo.size > PRUNE_THRESHOLD) {
            sessionResetSentTo.clear()
        }
    }

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

        // Store locally as SENDING before encryption so the message is never
        // lost even if session establishment or encryption fails.
        val entity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = plaintext,
            state = MessageState.SENDING.name,
            timestamp = now,
            expiresAt = null,
            isOutgoing = true,
        )
        messageDao.insert(entity)

        // Serialize ratchet operations per peer to prevent state divergence
        val peerMutex = peerLocks.computeIfAbsent(recipientId) { kotlinx.coroutines.sync.Mutex() }
        val envelope = try {
            peerMutex.withLock {
                var sessionKeys = keyManager.getCachedSessionKeys(recipientId)
                if (sessionKeys == null) {
                    sessionKeys = tryEstablishSession(recipientId, encapsulateIfAvailable = true)
                        ?: throw IllegalStateException("No session established with $recipientId")
                }
                cryptoEngine.encrypt(sessionKeys, plaintext.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            messageDao.updateState(messageId, MessageState.FAILED.name)
            throw e
        }

        // Update the stored message with the encrypted payload
        messageDao.updateEncryptedBody(messageId, envelope.ciphertext, envelope.nonce)

        // Sign the envelope for recipient verification
        val sigPayload = buildMessageSigPayload(senderId, recipientId, envelope.nonce, envelope.ciphertext)
        val signature = keyManager.sign(sigPayload)
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)
        val pqcSignatureB64 = signWithMlDsa(sigPayload)

        // Send to server
        try {
            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                    signature = signatureB64,
                    pqcSignature = pqcSignatureB64,
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

        // Serialize ratchet operations per peer — same lock used by sendMessage/fetch
        val peerMutex = peerLocks.computeIfAbsent(entity.recipientId) { kotlinx.coroutines.sync.Mutex() }
        try {
            val envelope = peerMutex.withLock {
                var sessionKeys = keyManager.getCachedSessionKeys(entity.recipientId)
                if (sessionKeys == null) {
                    sessionKeys = tryEstablishSession(entity.recipientId, encapsulateIfAvailable = true)
                        ?: throw IllegalStateException("No session for retry")
                }
                cryptoEngine.encrypt(sessionKeys, plaintext.toByteArray(Charsets.UTF_8))
            }

            val retrySenderId = keyManager.getUserId() ?: throw IllegalStateException("No identity")
            val retrySigPayload = buildMessageSigPayload(retrySenderId, entity.recipientId, envelope.nonce, envelope.ciphertext)
            val retrySignature = keyManager.sign(retrySigPayload)
            val retryPqcSig = signWithMlDsa(retrySigPayload)

            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = entity.recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                    signature = Base64.encodeToString(retrySignature, Base64.NO_WRAP),
                    pqcSignature = retryPqcSig,
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
        pruneInMemoryMaps()

        val pending = apiService.fetchPendingMessages()
        if (pending.isEmpty()) return

        val localUserId = keyManager.getUserId() ?: return

        val receivedIds = mutableListOf<String>()

        for (dto in pending) {
            // Check for control messages (session_reset signals)
            if (handleControlMessage(dto, receivedIds)) continue

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
            if (sessionKeys == null) {
                // If KEM ciphertext was present (peer expects PQC) but session
                // establishment failed, reject rather than silently downgrading.
                if (kemCiphertext != null) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "Rejecting message with KEM ciphertext: PQC session establishment failed for ${dto.senderId.take(8)}")
                    val rejectedEnvelope = try {
                        EncryptedEnvelope(
                            ciphertext = Base64.decode(dto.encryptedBody, Base64.NO_WRAP),
                            nonce = Base64.decode(dto.nonce, Base64.NO_WRAP),
                        )
                    } catch (_: IllegalArgumentException) {
                        EncryptedEnvelope(ciphertext = ByteArray(0), nonce = ByteArray(0))
                    }
                    storeRejected(dto.messageId, dto.senderId, localUserId, rejectedEnvelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                }
                continue
            }

            val envelope = try {
                EncryptedEnvelope(
                    ciphertext = Base64.decode(dto.encryptedBody, Base64.NO_WRAP),
                    nonce = Base64.decode(dto.nonce, Base64.NO_WRAP),
                )
            } catch (_: IllegalArgumentException) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                    "Rejecting message with invalid Base64 from ${dto.senderId.take(8)}")
                storeRejected(dto.messageId, dto.senderId, localUserId,
                    EncryptedEnvelope(ciphertext = ByteArray(0), nonce = ByteArray(0)), dto.timestamp)
                receivedIds.add(dto.messageId)
                continue
            }

            // Fetch contact once for signature checks and notification display
            var senderContact = contactDao.getByUserId(dto.senderId)

            // Reject all unsigned messages — signatures are mandatory.
            // Legacy contacts with signatureRequired=false are no longer exempt
            // to prevent impersonation via unsigned message injection.
            if (dto.signature.isEmpty()) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Rejecting unsigned message from ${dto.senderId.take(8)}")
                storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                receivedIds.add(dto.messageId)
                continue
            }

            // Verify sender signature (guaranteed non-empty after the check above)
            if (senderContact == null) {
                // Force session establishment to fetch and save the key bundle
                tryEstablishSession(dto.senderId, kemCiphertext)
                senderContact = contactDao.getByUserId(dto.senderId)
            }
            if (senderContact == null) {
                // Cannot verify — reject the message
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Cannot verify signature: unknown sender ${dto.messageId}")
                storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                receivedIds.add(dto.messageId)
                continue
            }
            val sigPayload = buildMessageSigPayload(dto.senderId, localUserId, envelope.nonce, envelope.ciphertext)
            val sig = try {
                Base64.decode(dto.signature, Base64.NO_WRAP)
            } catch (_: Exception) {
                receivedIds.add(dto.messageId)
                continue
            }
            val valid = cryptoEngine.verify(sigPayload, sig, senderContact.publicSigningKey)
            if (!valid) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Signature verification failed for ${dto.messageId}")
                countDecryptFailure(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp, receivedIds)
                continue
            }
            // ML-DSA-65 post-quantum signature verification.
            // If the sender has a PQC signing key, the signature is REQUIRED —
            // omitting it would bypass the hybrid authentication model.
            if (senderContact.pqcSigningKey.isNotEmpty() && dto.pqcSignature.isEmpty()) {
                // Missing ML-DSA signature from a PQC-capable sender.  This may
                // be a timing issue (message sent before the sender's PQC upgrade
                // completed) rather than an attack.  Skip without ACKing so the
                // message is retried on next sync — same approach as control
                // messages (see handleControlMessage).  After MAX_PQC_SIG_MISS_RETRIES
                // sync cycles, permanently reject so the message doesn't retry forever.
                val misses = (pqcSigMissCounts[dto.messageId] ?: 0) + 1
                pqcSigMissCounts[dto.messageId] = misses
                if (misses >= MAX_PQC_SIG_MISS_RETRIES) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "ML-DSA signature missing from PQC-capable sender ${dto.senderId.take(8)}, rejecting after $misses attempts")
                    pqcSigMissCounts.remove(dto.messageId)
                    storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                } else {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "ML-DSA signature missing from PQC-capable sender ${dto.senderId.take(8)}, skipping for retry ($misses/$MAX_PQC_SIG_MISS_RETRIES)")
                }
                continue
            }
            if (dto.pqcSignature.isNotEmpty() && senderContact.pqcSigningKey.isNotEmpty()) {
                val pqcSig = try {
                    Base64.decode(dto.pqcSignature, Base64.NO_WRAP)
                } catch (_: Exception) {
                    receivedIds.add(dto.messageId)
                    continue
                }
                val pqcValid = try {
                    pqcProvider.verify(sigPayload, pqcSig, senderContact.pqcSigningKey)
                } catch (_: Exception) { false }
                if (!pqcValid) {
                    // Invalid ML-DSA signature is an authentication failure,
                    // not a decryption failure.  Reject without counting toward
                    // session reset.
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "ML-DSA signature verification failed for ${dto.messageId}")
                    storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                    continue
                }
            }

            val peerMutex = peerLocks.computeIfAbsent(dto.senderId) { kotlinx.coroutines.sync.Mutex() }
            val plaintext = try {
                val result = peerMutex.withLock {
                    cryptoEngine.decrypt(sessionKeys, envelope)
                }
                decryptFailCounts.remove(dto.messageId)
                pqcSigMissCounts.remove(dto.messageId)
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
                            pqcSigMissCounts.remove(dto.messageId)
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

            val isActive = conversationId == activeConversationId
            updateConversationPreview(
                conversationId,
                dto.senderId,
                plaintextStr,
                dto.timestamp,
                incrementUnread = !isActive,
            )

            // Show notification for messages outside the active conversation
            if (!isActive) {
                val senderName = senderContact?.displayName
                    ?: dto.senderId.take(8)
                notificationManager.showMessageNotification(
                    senderId = dto.senderId,
                    senderName = senderName,
                    messageBody = plaintextStr,
                    conversationId = conversationId,
                )
            }
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
        encapsulateIfAvailable: Boolean = false,
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
            val pqcSignKey = if (bundle.pqcSigningKey.isNotEmpty()) {
                try {
                    Base64.decode(bundle.pqcSigningKey, Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    ByteArray(0)
                }
            } else ByteArray(0)

            // Pass PQC key when:
            //  (a) decapsulating inbound KEM from a received message, OR
            //  (b) encapsulating for a NEW outgoing session (sender path).
            // Do NOT encapsulate when receiving a classical-only message —
            // that would create a hybrid root key that the sender never derived.
            val usePqcKey = inboundKemCiphertext != null || encapsulateIfAvailable
            val sessionKeys = cryptoEngine.establishSession(
                localIdentity = localKeyPair,
                remotePublicBundle = PublicKeyBundle(
                    publicSigningKey = pubSignKey,
                    publicIdentityKey = pubIdKey,
                    pqcEncapsulationKey = if (usePqcKey) pqcKey else ByteArray(0),
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
                    pqcSigningKey = pqcSignKey,
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
                    contactDao.upsert(existingContact.copy(
                        pqcEstablished = true,
                        pqcSigningKey = pqcSignKey,
                    ))
                }
            }

            sessionKeys
        } catch (e: Exception) {
            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo",
                "Session establishment failed for ${remoteUserId.take(8)}: ${e.message}", e)
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

            // Notify the sender that they need to re-establish their session
            if (senderId !in sessionResetSentTo) {
                try {
                    sessionResetSender.send(senderId)
                    sessionResetSentTo.add(senderId)
                } catch (e: Exception) {
                    if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "Failed to send session_reset to ${senderId.take(8)}: ${e.message}")
                }
            }
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

    /**
     * Check if a pending message is a control message (e.g. session_reset).
     * Returns true if the message was handled and should be skipped.
     */
    private suspend fun handleControlMessage(
        dto: com.chatcontroll.app.data.remote.dto.PendingMessageDto,
        receivedIds: MutableList<String>,
    ): Boolean {
        val nonceBytes = try {
            Base64.decode(dto.nonce, Base64.NO_WRAP)
        } catch (_: Exception) { return false }

        val nonceStr = try {
            String(nonceBytes, Charsets.UTF_8)
        } catch (_: Exception) { return false }

        if (!nonceStr.startsWith("{\"ctrl\":")) return false

        val ctrl = try {
            headerJson.parseToJsonElement(nonceStr)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("ctrl")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content
        } catch (_: Exception) { return false }

        val isAccountDeleted = ctrl == "account_deleted"
        if (ctrl != "session_reset" && !isAccountDeleted) return false

        // Verify signature using the sender's CURRENT key from the server
        // (their locally stored key may be outdated after rotation).
        // For account_deleted the sender's identity may already be gone from
        // the server, so fall back to the locally stored key.
        val bundle = try {
            apiService.fetchKeyBundle(dto.senderId)
        } catch (_: Exception) { null }

        val localUserId = keyManager.getUserId() ?: ""

        // Determine the signing key to verify against
        val existingContact = contactDao.getByUserId(dto.senderId)
        val pubSignKey = if (bundle != null) {
            try { Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP) } catch (_: Exception) { null }
        } else {
            existingContact?.publicSigningKey
        }
        val pqcSignKey = if (bundle != null && bundle.pqcSigningKey.isNotEmpty()) {
            try { Base64.decode(bundle.pqcSigningKey, Base64.NO_WRAP) } catch (_: Exception) { null }
        } else {
            existingContact?.pqcSigningKey?.takeIf { it.isNotEmpty() }
        }
        val pubIdKey = if (bundle != null) {
            try { Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP) } catch (_: Exception) { null }
        } else {
            existingContact?.publicIdentityKey
        }

        // Reject unsigned control messages permanently — no signature is
        // never valid, so ACK to remove from the pending queue.
        if (dto.signature.isEmpty()) {
            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                "Rejecting unsigned $ctrl from ${dto.senderId.take(8)}")
            receivedIds.add(dto.messageId)
            return true
        }

        // If the signing key is unavailable (transient network error on key
        // fetch), skip without ACKing so the message is retried on next sync.
        if (pubSignKey == null) {
            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                "Postponing $ctrl from ${dto.senderId.take(8)}: no signing key available")
            return true
        }

        val sigPayload = buildMessageSigPayload(dto.senderId, localUserId, nonceBytes, ByteArray(0))
        val sig = try {
            Base64.decode(dto.signature, Base64.NO_WRAP)
        } catch (_: Exception) {
            receivedIds.add(dto.messageId)
            return true
        }
        val valid = cryptoEngine.verify(sigPayload, sig, pubSignKey)
        if (!valid) {
            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                "$ctrl signature invalid from ${dto.senderId.take(8)}")
            receivedIds.add(dto.messageId)
            return true
        }
        // ML-DSA-65 verification for control messages.
        // If the sender has a PQC signing key, the signature is REQUIRED.
        // Skip without ACKing (don't add to receivedIds) so the message is retried
        // on next sync — the sender may have just upgraded to PQC and our local
        // contact DB hasn't received their signing key yet.
        if (pqcSignKey != null && pqcSignKey.isNotEmpty() && dto.pqcSignature.isEmpty()) {
            val misses = (ctrlPqcSigMissCounts[dto.messageId] ?: 0) + 1
            ctrlPqcSigMissCounts[dto.messageId] = misses
            if (misses >= MAX_PQC_SIG_MISS_RETRIES) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                    "$ctrl ML-DSA signature missing from PQC-capable sender ${dto.senderId.take(8)}, rejecting after $misses attempts")
                ctrlPqcSigMissCounts.remove(dto.messageId)
                receivedIds.add(dto.messageId)
            } else {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                    "$ctrl ML-DSA signature missing from PQC-capable sender ${dto.senderId.take(8)}, skipping for retry ($misses/$MAX_PQC_SIG_MISS_RETRIES)")
            }
            return true
        }
        if (dto.pqcSignature.isNotEmpty() && pqcSignKey != null && pqcSignKey.isNotEmpty()) {
            val pqcSig = try {
                Base64.decode(dto.pqcSignature, Base64.NO_WRAP)
            } catch (_: Exception) {
                receivedIds.add(dto.messageId)
                return true
            }
            val pqcValid = try {
                pqcProvider.verify(sigPayload, pqcSig, pqcSignKey)
            } catch (_: Exception) { false }
            if (!pqcValid) {
                if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                    "$ctrl ML-DSA signature invalid from ${dto.senderId.take(8)}")
                receivedIds.add(dto.messageId)
                return true
            }
        }

        if (!isAccountDeleted && existingContact != null && pubIdKey != null) {
            // Update the contact's stored keys to the new ones.
            // Reset pqcEstablished so the classical-only re-establish
            // isn't rejected by the PQC downgrade guard.
            contactDao.upsert(existingContact.copy(
                publicSigningKey = pubSignKey,
                publicIdentityKey = pubIdKey,
                pqcSigningKey = pqcSignKey ?: ByteArray(0),
                pqcEstablished = false,
            ))
        }

        // Clear stale session for this peer (both persisted and in-memory ratchet state)
        val staleSessionId = keyManager.getCachedSessionKeys(dto.senderId)?.sessionId
        keyManager.clearSessionForPeer(dto.senderId)
        if (staleSessionId != null) cryptoEngine.clearSession(staleSessionId)

        // Mark conversation as needing session re-establishment
        conversationDao.setNeedsSessionReset(dto.senderId, true)
        if (isAccountDeleted) {
            conversationDao.setPeerDeleted(dto.senderId, true)
        }

        // Record event in the chat history
        val eventState = if (isAccountDeleted) MessageState.ACCOUNT_DELETED else MessageState.KEY_ROTATED_REMOTE
        val conversationId = getOrCreateConversationId(dto.senderId)
        messageDao.insert(MessageEntity(
            id = "keychange-${dto.messageId}",
            conversationId = conversationId,
            senderId = dto.senderId,
            recipientId = localUserId,
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = "",
            state = eventState.name,
            timestamp = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            expiresAt = null,
            isOutgoing = false,
        ))

        if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.i("MessageRepo",
            "$ctrl received from ${dto.senderId.take(8)}, session cleared")

        receivedIds.add(dto.messageId)
        return true
    }

    override suspend fun reEstablishSession(contactId: String) {
        // Clear old session state (both persisted and in-memory ratchet state)
        val oldSessionId = keyManager.getCachedSessionKeys(contactId)?.sessionId
        keyManager.clearSessionForPeer(contactId)
        if (oldSessionId != null) cryptoEngine.clearSession(oldSessionId)

        // Fetch fresh key bundle and establish new session (with PQC if available)
        val sessionKeys = tryEstablishSession(contactId, encapsulateIfAvailable = true)
            ?: throw IllegalStateException("Could not re-establish session with $contactId")

        // Clear the flag — sending is now allowed again
        conversationDao.setNeedsSessionReset(contactId, false)

        // Clear dedup tracking so future decrypt failures from this peer are handled
        sessionResetSentTo.remove(contactId)
    }

    /** Sign data with ML-DSA-65 if keys are available, returning base64 or empty string. */
    private fun signWithMlDsa(data: ByteArray): String {
        return try {
            val mlDsaPrivKey = keyManager.getMlDsaPrivateKey() ?: return ""
            try {
                Base64.encodeToString(pqcProvider.sign(data, mlDsaPrivKey), Base64.NO_WRAP)
            } finally {
                mlDsaPrivKey.fill(0)
            }
        } catch (e: Exception) {
            if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "ML-DSA signing failed: ${e.message}")
            ""
        }
    }

    companion object {
        /** After this many failed decrypt attempts, acknowledge the message to
         *  prevent it from poisoning the pending queue forever. */
        private const val MAX_DECRYPT_RETRIES = 3
        /** Sync cycles to skip a message with a missing PQC signature before
         *  permanently rejecting it (gives time for key bundle propagation). */
        private const val MAX_PQC_SIG_MISS_RETRIES = 3

        /** Evict stale entries from in-memory maps when they exceed this size. */
        private const val PRUNE_THRESHOLD = 200
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
                isApproved = existing?.isApproved ?: false,
                needsSessionReset = existing?.needsSessionReset ?: false,
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

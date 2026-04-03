package com.chatcontroll.app.data.remote

import android.util.Base64
import com.chatcontroll.app.data.remote.dto.AckRequest
import com.chatcontroll.app.data.remote.dto.BootstrapRequest
import com.chatcontroll.app.data.remote.dto.BootstrapResponse
import com.chatcontroll.app.data.remote.dto.KeyBundleDto
import com.chatcontroll.app.data.remote.dto.PendingMessageDto
import com.chatcontroll.app.data.remote.dto.PushTokenRequest
import com.chatcontroll.app.data.remote.dto.ResolveShareCodeResponse
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import com.chatcontroll.app.data.remote.dto.SendMessageResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process mock of the relay server for development and testing.
 * Replace with [KtorApiService] when a real backend is available.
 */
@Singleton
class MockApiService @Inject constructor() : ApiService {

    private val mutex = Mutex()

    // In-memory server state
    private val keyBundles = mutableMapOf<String, KeyBundleDto>()
    private val shareCodes = mutableMapOf<String, String>() // shareCode → userId
    private val pendingMessages = mutableMapOf<String, MutableList<PendingMessageDto>>() // recipientId → messages
    private val pushTokens = mutableMapOf<String, String>() // userId → token

    private var currentUserId: String? = null

    override suspend fun bootstrapIdentity(request: BootstrapRequest): BootstrapResponse = mutex.withLock {
        val userId = UUID.randomUUID().toString().replace("-", "").take(16)
        val shareCode = deriveShareCode(request.publicIdentityKey)

        keyBundles[userId] = KeyBundleDto(
            userId = userId,
            publicSigningKey = request.publicSigningKey,
            publicIdentityKey = request.publicIdentityKey,
            pqcEncapsulationKey = request.pqcEncapsulationKey,
        )
        shareCodes[shareCode] = userId
        currentUserId = userId

        if (request.fcmToken != null) {
            pushTokens[userId] = request.fcmToken
        }

        BootstrapResponse(userId = userId, shareCode = shareCode)
    }

    override suspend fun fetchKeyBundle(userId: String): KeyBundleDto? = mutex.withLock {
        keyBundles[userId]
    }

    override suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse? = mutex.withLock {
        val userId = shareCodes[shareCode] ?: return@withLock null
        val bundle = keyBundles[userId] ?: return@withLock null
        ResolveShareCodeResponse(
            userId = bundle.userId,
            publicSigningKey = bundle.publicSigningKey,
            publicIdentityKey = bundle.publicIdentityKey,
            pqcEncapsulationKey = bundle.pqcEncapsulationKey,
        )
    }

    override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse = mutex.withLock {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val pending = PendingMessageDto(
            messageId = messageId,
            senderId = currentUserId ?: "unknown",
            encryptedBody = request.encryptedBody,
            nonce = request.nonce,
            ephemeralPublicKey = request.ephemeralPublicKey,
            timestamp = timestamp,
        )

        pendingMessages.getOrPut(request.recipientId) { mutableListOf() }.add(pending)

        SendMessageResponse(messageId = messageId, timestamp = timestamp)
    }

    override suspend fun fetchPendingMessages(): List<PendingMessageDto> = mutex.withLock {
        val userId = currentUserId ?: return@withLock emptyList()
        val messages = pendingMessages[userId]?.toList() ?: emptyList()
        messages
    }

    override suspend fun acknowledgeMessages(request: AckRequest): Unit = mutex.withLock {
        val userId = currentUserId ?: return@withLock
        pendingMessages[userId]?.removeAll { it.messageId in request.messageIds }
    }

    override suspend fun registerPushToken(request: PushTokenRequest): Unit = mutex.withLock {
        val userId = currentUserId ?: return@withLock
        pushTokens[userId] = request.token
    }

    override suspend fun unregisterPushToken(): Unit = mutex.withLock {
        val userId = currentUserId ?: return@withLock
        pushTokens.remove(userId)
    }

    private fun deriveShareCode(publicIdentityKeyBase64: String): String {
        val keyBytes = Base64.decode(publicIdentityKeyBase64, Base64.NO_WRAP)
        val hash = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return Base64.encodeToString(hash.copyOfRange(0, 12), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}

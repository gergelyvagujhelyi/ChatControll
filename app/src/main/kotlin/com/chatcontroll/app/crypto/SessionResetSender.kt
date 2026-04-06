package com.chatcontroll.app.crypto

import android.util.Base64
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a signed `session_reset` control message to a peer.
 *
 * Used by both [SettingsRepositoryImpl] (wipe-all) and
 * [MessageRepositoryImpl] (decrypt-failure recovery) to avoid
 * duplicating the signing + send logic.
 */
@Singleton
class SessionResetSender @Inject constructor(
    private val keyManager: KeyManager,
    private val apiService: ApiService,
) {
    /**
     * Send a session_reset control message to [recipientId].
     * Returns silently if no local identity exists.
     */
    suspend fun send(recipientId: String) {
        val senderId = keyManager.getUserId() ?: return
        val controlPayload = """{"ctrl":"session_reset"}"""
        val nonceBytes = controlPayload.toByteArray(Charsets.UTF_8)
        val bodyBytes = ByteArray(0)

        val sigPayload = buildMessageSigPayload(senderId, recipientId, nonceBytes, bodyBytes)
        val signature = keyManager.sign(sigPayload)

        apiService.sendMessage(
            SendMessageRequest(
                recipientId = recipientId,
                encryptedBody = Base64.encodeToString(bodyBytes, Base64.NO_WRAP),
                nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP),
                signature = Base64.encodeToString(signature, Base64.NO_WRAP),
            )
        )
    }
}

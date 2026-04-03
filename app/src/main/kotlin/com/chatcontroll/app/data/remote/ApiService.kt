package com.chatcontroll.app.data.remote

import com.chatcontroll.app.data.remote.dto.AckRequest
import com.chatcontroll.app.data.remote.dto.BootstrapRequest
import com.chatcontroll.app.data.remote.dto.BootstrapResponse
import com.chatcontroll.app.data.remote.dto.KeyBundleDto
import com.chatcontroll.app.data.remote.dto.PendingMessageDto
import com.chatcontroll.app.data.remote.dto.PushTokenRequest
import com.chatcontroll.app.data.remote.dto.ResolveShareCodeResponse
import com.chatcontroll.app.data.remote.dto.CallSignalRequest
import com.chatcontroll.app.data.remote.dto.CallSignalResponse
import com.chatcontroll.app.data.remote.dto.IceServersResponse
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import com.chatcontroll.app.data.remote.dto.SendMessageResponse

/**
 * API contract for the ChatControll relay server.
 *
 * The server is a minimal envelope relay — it never sees plaintext message content.
 * It stores only:
 * - userId → publicKeyBundle mapping (for key discovery)
 * - userId → FCM push token (for notification delivery)
 * - Pending encrypted envelopes (deleted after delivery acknowledgement)
 *
 * Endpoints:
 * POST   /v1/identity/bootstrap         — register a guest identity
 * GET    /v1/identity/{userId}/keys      — fetch a user's public key bundle
 * POST   /v1/identity/resolve            — resolve a share code to a key bundle
 * POST   /v1/messages/send               — submit an encrypted envelope for relay
 * GET    /v1/messages/pending             — fetch pending envelopes
 * POST   /v1/messages/ack                — acknowledge receipt of messages
 * POST   /v1/push/register               — register FCM token
 * DELETE /v1/push/register               — unregister FCM token
 */
interface ApiService {
    suspend fun bootstrapIdentity(request: BootstrapRequest): BootstrapResponse
    suspend fun fetchKeyBundle(userId: String): KeyBundleDto?
    suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse?
    suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse
    suspend fun fetchPendingMessages(): List<PendingMessageDto>
    suspend fun acknowledgeMessages(request: AckRequest)
    suspend fun registerPushToken(request: PushTokenRequest)
    suspend fun unregisterPushToken()
    suspend fun sendCallSignal(request: CallSignalRequest): CallSignalResponse
    suspend fun getIceServers(): IceServersResponse
}

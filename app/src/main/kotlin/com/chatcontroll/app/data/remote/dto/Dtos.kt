package com.chatcontroll.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class BootstrapRequest(
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String,
    val fcmToken: String? = null,
)

@Serializable
data class BootstrapResponse(
    val userId: String,
    val shareCode: String,
)

@Serializable
data class KeyBundleDto(
    val userId: String,
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String,
)

@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val encryptedBody: String,
    val nonce: String,
    val ephemeralPublicKey: String = "",
    val signature: String = "",
)

@Serializable
data class SendMessageResponse(
    val messageId: String,
    val timestamp: Long,
)

@Serializable
data class PendingMessageDto(
    val messageId: String,
    val senderId: String,
    val encryptedBody: String,
    val nonce: String,
    val ephemeralPublicKey: String = "",
    val signature: String = "",
    val timestamp: Long,
)

@Serializable
data class AckRequest(
    val messageIds: List<String>,
)

@Serializable
data class PushTokenRequest(
    val token: String,
    val platform: String = "android",
)

@Serializable
data class ResolveShareCodeResponse(
    val userId: String,
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class CallSignalRequest(
    val recipientId: String,
    val signalType: String,
    val callId: String,
    val encryptedPayload: String,
    val signature: String = "",
)

@Serializable
data class CallSignalResponse(
    val delivered: Boolean,
)

@Serializable
data class CallSignalDto(
    val senderId: String,
    val signalType: String,
    val callId: String,
    val encryptedPayload: String,
    val signature: String = "",
)

@Serializable
data class IceServerDto(
    val urls: String,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceServersResponse(
    val iceServers: List<IceServerDto>,
)

@Serializable
data class KeyRotationRequest(
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String? = null,
    val newKeyProof: String,
)

@Serializable
data class KeyRotationResponse(
    val status: String,
    val shareCode: String,
)

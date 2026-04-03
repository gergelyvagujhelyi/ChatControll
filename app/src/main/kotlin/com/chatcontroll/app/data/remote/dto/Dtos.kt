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

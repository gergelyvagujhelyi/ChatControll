package com.chatcontroll.app.notification

import com.chatcontroll.app.domain.repository.MessageRepository
import com.chatcontroll.app.domain.repository.PushTokenRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var pushTokenRepository: PushTokenRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var notificationManager: ChatNotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch {
            try {
                pushTokenRepository.registerToken(token)
            } catch (_: Exception) {
                // Will retry via WorkManager
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // The push notification is a "wake-up" signal only.
        // We never send message content through FCM — the encrypted envelope
        // is fetched directly from the relay server.
        scope.launch {
            try {
                messageRepository.fetchPendingFromServer()

                // Show a generic notification — content comes from local decryption
                val senderId = message.data["senderId"] ?: "Unknown"
                val conversationId = message.data["conversationId"] ?: return@launch

                notificationManager.showMessageNotification(
                    senderId = senderId,
                    senderName = senderId.take(8),
                    messageBody = "New encrypted message",
                    conversationId = conversationId,
                )
            } catch (_: Exception) {
                // Best-effort notification
            }
        }
    }
}

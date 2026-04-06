package com.chatcontroll.app.notification

import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.domain.repository.MessageRepository
import com.chatcontroll.app.domain.repository.PushTokenRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var pushTokenRepository: PushTokenRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var webSocketClient: WebSocketClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

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
        // Ensure WebSocket is connected so buffered call signals are delivered
        webSocketClient.ensureConnected()

        // Fetch pending messages — notifications are shown by
        // MessageRepositoryImpl after decryption with actual content.
        scope.launch {
            try {
                messageRepository.fetchPendingFromServer()
            } catch (_: Exception) {
                // Best-effort sync
            }
        }
    }
}

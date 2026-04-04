package com.chatcontroll.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chatcontroll.app.R
import com.chatcontroll.app.MainActivity
import com.chatcontroll.app.domain.model.LockScreenPreviewMode
import com.chatcontroll.app.domain.model.PrivacySettings
import com.chatcontroll.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New message notifications"
            // Default to no lock screen content — respects privacy settings
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background sync status"
        }

        manager.createNotificationChannel(messageChannel)
        manager.createNotificationChannel(serviceChannel)
    }

    suspend fun showMessageNotification(
        senderId: String,
        senderName: String,
        messageBody: String,
        conversationId: String,
    ) {
        if (!hasNotificationPermission()) return

        val settings = settingsRepository.getPrivacySettings().firstOrNull() ?: PrivacySettings()

        val (title, body) = when (settings.lockScreenPreview) {
            LockScreenPreviewMode.SHOW_ALL -> senderName to messageBody
            LockScreenPreviewMode.SENDER_ONLY -> senderName to "New message"
            LockScreenPreviewMode.HIDE_BODY -> senderName to "New message"
            LockScreenPreviewMode.HIDE_ALL -> "ChatControll" to "New message"
        }

        val visibility = when (settings.lockScreenPreview) {
            LockScreenPreviewMode.SHOW_ALL -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.SENDER_ONLY -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.HIDE_BODY -> NotificationCompat.VISIBILITY_PRIVATE
            LockScreenPreviewMode.HIDE_ALL -> NotificationCompat.VISIBILITY_SECRET
        }

        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CONTACT_ID, senderId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(visibility)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        NotificationManagerCompat.from(context)
            .notify(conversationId.hashCode(), notification)
    }

    fun cancelNotification(conversationId: String) {
        NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "background_service"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}

package com.chatcontroll.app.data.repository

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import com.chatcontroll.app.domain.model.DisappearingDuration
import com.chatcontroll.app.domain.model.LockScreenPreviewMode
import com.chatcontroll.app.domain.model.PrivacySettings
import com.chatcontroll.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "privacy_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val keyManager: KeyManager,
    private val apiService: ApiService,
    private val webSocketClient: WebSocketClient,
) : SettingsRepository {

    override fun getPrivacySettings(): Flow<PrivacySettings> {
        return context.settingsDataStore.data.map { prefs ->
            PrivacySettings(
                lockScreenPreview = prefs[LOCK_SCREEN_PREVIEW]
                    ?.let { LockScreenPreviewMode.valueOf(it) }
                    ?: LockScreenPreviewMode.HIDE_ALL,
                disappearingMessagesDefault = prefs[DISAPPEARING_DEFAULT]
                    ?.let { DisappearingDuration.valueOf(it) }
                    ?: DisappearingDuration.OFF,
                readReceipts = prefs[READ_RECEIPTS] ?: false,
                screenSecurity = prefs[SCREEN_SECURITY] ?: true,
                localDataRetentionDays = prefs[DATA_RETENTION_DAYS] ?: 90,
            )
        }
    }

    override suspend fun updatePrivacySettings(settings: PrivacySettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[LOCK_SCREEN_PREVIEW] = settings.lockScreenPreview.name
            prefs[DISAPPEARING_DEFAULT] = settings.disappearingMessagesDefault.name
            prefs[READ_RECEIPTS] = settings.readReceipts
            prefs[SCREEN_SECURITY] = settings.screenSecurity
            prefs[DATA_RETENTION_DAYS] = settings.localDataRetentionDays
        }
    }

    override suspend fun wipeLocalData() {
        // Notify all contacts that our session is being invalidated, and
        // delete the server-side identity BEFORE wiping local keys.
        // Best-effort — don't block wipe if the server is unreachable.
        try {
            val senderId = keyManager.getUserId()
            if (senderId != null) {
                val contacts = contactDao.getAll().first()
                for (contact in contacts) {
                    try {
                        sendSessionReset(senderId, contact.userId)
                    } catch (_: Exception) { /* best effort */ }
                }
                try {
                    apiService.deleteIdentity()
                } catch (_: Exception) { /* best effort */ }
            }
        } catch (_: Exception) { /* best effort */ }

        // Disconnect WebSocket and wipe all local state
        webSocketClient.disconnect()
        messageDao.deleteAll()
        conversationDao.deleteAll()
        contactDao.deleteAll()
        keyManager.wipeAll()
        context.settingsDataStore.edit { it.clear() }
    }

    private suspend fun sendSessionReset(senderId: String, recipientId: String) {
        val controlPayload = """{"ctrl":"session_reset"}"""
        val nonceBytes = controlPayload.toByteArray(Charsets.UTF_8)
        val bodyBytes = ByteArray(0)

        val sigPayload = lengthPrefixed(senderId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(recipientId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(nonceBytes) + bodyBytes
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

    private fun lengthPrefixed(data: ByteArray): ByteArray {
        return ByteBuffer.allocate(4).putInt(data.size).array() + data
    }

    companion object {
        private val LOCK_SCREEN_PREVIEW = stringPreferencesKey("lock_screen_preview")
        private val DISAPPEARING_DEFAULT = stringPreferencesKey("disappearing_default")
        private val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        private val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        private val DATA_RETENTION_DAYS = intPreferencesKey("data_retention_days")
    }
}

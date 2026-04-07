package com.chatcontroll.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.crypto.SessionResetSender
import com.chatcontroll.app.crypto.SessionResetSender.Companion.CTRL_ACCOUNT_DELETED
import com.chatcontroll.app.data.local.AppDatabase
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.WebSocketClient
import com.chatcontroll.app.domain.model.DisappearingDuration
import com.chatcontroll.app.domain.model.LockScreenPreviewMode
import com.chatcontroll.app.domain.model.PrivacySettings
import com.chatcontroll.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "privacy_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val keyManager: KeyManager,
    private val apiService: ApiService,
    private val webSocketClient: WebSocketClient,
    private val sessionResetSender: SessionResetSender,
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
        val senderId = keyManager.getUserId()
        if (senderId != null) {
            // Notify contacts — best-effort
            try {
                val contacts = contactDao.getAll().first()
                for (contact in contacts) {
                    try {
                        sessionResetSender.send(contact.userId, SessionResetSender.CTRL_ACCOUNT_DELETED)
                    } catch (_: Exception) { /* best effort */ }
                }
            } catch (_: Exception) { /* best effort */ }

            // Delete server identity BEFORE wiping local keys.
            // If this fails, keys are preserved so the user can retry.
            apiService.deleteIdentity()
        }

        // Server identity deleted (or no identity) — wipe local state
        wipeLocal()
    }

    override suspend fun retryServerDeletion() {
        apiService.deleteIdentity()
        wipeLocal()
    }

    override suspend fun wipeLocalOnly() {
        wipeLocal()
    }

    private suspend fun wipeLocal() {
        webSocketClient.disconnect()
        // Clear data via DAOs while the DB connection is still valid,
        // then close the database to release the SQLCipher connection
        // before deleting the file. Without close(), active Room Flow
        // collectors would crash querying the deleted file.
        messageDao.deleteAll()
        conversationDao.deleteAll()
        contactDao.deleteAll()
        database.close()
        keyManager.wipeAll()
        context.deleteDatabase("chatcontroll.db")
        context.settingsDataStore.edit { it.clear() }
        // Kill the process so the Hilt singleton graph (including the now-closed
        // AppDatabase) is fully recreated on next launch. Without this, any
        // component accessing a DAO after wipe crashes with IllegalStateException
        // because the @Singleton AppDatabase reference is dead.
        kotlin.system.exitProcess(0)
    }

    companion object {
        private val LOCK_SCREEN_PREVIEW = stringPreferencesKey("lock_screen_preview")
        private val DISAPPEARING_DEFAULT = stringPreferencesKey("disappearing_default")
        private val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        private val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        private val DATA_RETENTION_DAYS = intPreferencesKey("data_retention_days")
    }
}

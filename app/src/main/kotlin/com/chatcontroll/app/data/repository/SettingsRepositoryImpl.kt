package com.chatcontroll.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.domain.model.DisappearingDuration
import com.chatcontroll.app.domain.model.LockScreenPreviewMode
import com.chatcontroll.app.domain.model.PrivacySettings
import com.chatcontroll.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        messageDao.deleteAll()
        conversationDao.deleteAll()
        contactDao.deleteAll()
        keyManager.wipeAll()
        context.settingsDataStore.edit { it.clear() }
    }

    companion object {
        private val LOCK_SCREEN_PREVIEW = stringPreferencesKey("lock_screen_preview")
        private val DISAPPEARING_DEFAULT = stringPreferencesKey("disappearing_default")
        private val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        private val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        private val DATA_RETENTION_DAYS = intPreferencesKey("data_retention_days")
    }
}

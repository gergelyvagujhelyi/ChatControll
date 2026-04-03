package com.chatcontroll.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chatcontroll.app.data.remote.ApiService
import com.chatcontroll.app.data.remote.dto.PushTokenRequest
import com.chatcontroll.app.domain.repository.PushTokenRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pushDataStore by preferencesDataStore(name = "push_token")

@Singleton
class PushTokenRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
) : PushTokenRepository {

    private val tokenKey = stringPreferencesKey("fcm_token")

    override suspend fun registerToken(token: String) {
        apiService.registerPushToken(PushTokenRequest(token = token))
        context.pushDataStore.edit { it[tokenKey] = token }
    }

    override suspend fun unregisterToken() {
        apiService.unregisterPushToken()
        context.pushDataStore.edit { it.remove(tokenKey) }
    }

    override suspend fun getStoredToken(): String? {
        return context.pushDataStore.data.map { it[tokenKey] }.firstOrNull()
    }
}

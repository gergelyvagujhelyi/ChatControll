package com.chatcontroll.app.domain.repository

interface PushTokenRepository {
    suspend fun registerToken(token: String)
    suspend fun unregisterToken()
    suspend fun getStoredToken(): String?
}

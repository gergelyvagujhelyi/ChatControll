package com.chatcontroll.app.domain.repository

import com.chatcontroll.app.domain.model.Contact
import com.chatcontroll.app.domain.model.Identity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    suspend fun getOrCreateIdentity(): Identity
    suspend fun hasIdentity(): Boolean
    suspend fun getIdentity(): Identity?
    suspend fun addContact(shareCode: String): Contact
    fun getContacts(): Flow<List<Contact>>
    suspend fun getContact(userId: String): Contact?
    suspend fun publishKeyBundle()
    suspend fun fetchKeyBundle(userId: String): Contact?
}

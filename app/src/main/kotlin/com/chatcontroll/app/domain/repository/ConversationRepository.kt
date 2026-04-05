package com.chatcontroll.app.domain.repository

import com.chatcontroll.app.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getMessageRequests(): Flow<List<Conversation>>
    fun getConversation(conversationId: String): Flow<Conversation?>
    suspend fun getOrCreateConversation(contactId: String): String
    suspend fun approveConversation(conversationId: String)
    suspend fun clearUnread(conversationId: String)
    suspend fun deleteConversation(conversationId: String)
}

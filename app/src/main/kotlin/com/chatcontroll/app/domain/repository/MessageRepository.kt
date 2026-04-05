package com.chatcontroll.app.domain.repository

import com.chatcontroll.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun sendMessage(conversationId: String, recipientId: String, plaintext: String): Message
    suspend fun markDelivered(messageId: String)
    suspend fun markSeen(messageId: String)
    suspend fun retryFailed(messageId: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesOlderThan(conversationId: String, timestampMillis: Long)
    suspend fun fetchPendingFromServer()
    suspend fun reEstablishSession(contactId: String)
    /** Set the conversation the user is currently viewing. Messages arriving for
     *  this conversation will not increment the unread counter. Pass null on exit. */
    fun setActiveConversation(conversationId: String?)
}

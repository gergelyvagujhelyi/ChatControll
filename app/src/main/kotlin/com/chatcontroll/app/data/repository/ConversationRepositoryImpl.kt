package com.chatcontroll.app.data.repository

import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import com.chatcontroll.app.data.local.entity.ConversationEntity
import com.chatcontroll.app.domain.model.Conversation
import com.chatcontroll.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getMessageRequests(): Flow<List<Conversation>> {
        return conversationDao.getMessageRequests().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.getById(conversationId).map { it?.toDomain() }
    }

    override suspend fun getOrCreateConversation(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) {
            // Auto-approve if user explicitly adds this contact
            if (!existing.isApproved) {
                conversationDao.approve(existing.id)
            }
            return existing.id
        }

        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                contactDisplayName = contactId.take(8),
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = true,
            )
        )
        return id
    }

    override suspend fun approveConversation(conversationId: String) {
        conversationDao.approve(conversationId)
    }

    override suspend fun clearUnread(conversationId: String) {
        conversationDao.clearUnread(conversationId)
    }

    override suspend fun deleteConversation(conversationId: String) {
        messageDao.deleteAllForConversation(conversationId)
        conversationDao.delete(conversationId)
    }
}

private fun ConversationEntity.toDomain(): Conversation {
    return Conversation(
        id = id,
        contactId = contactId,
        contactDisplayName = contactDisplayName,
        lastMessagePreview = lastMessagePreview,
        lastMessageTimestamp = lastMessageTimestamp?.let { Instant.fromEpochMilliseconds(it) },
        unreadCount = unreadCount,
        isEncrypted = isEncrypted,
        isApproved = isApproved,
        needsSessionReset = needsSessionReset,
        peerDeleted = peerDeleted,
    )
}

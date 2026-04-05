package com.chatcontroll.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chatcontroll.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE isApproved = 1 ORDER BY lastMessageTimestamp DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isApproved = 0 ORDER BY lastMessageTimestamp DESC")
    fun getMessageRequests(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET isApproved = 1 WHERE id = :conversationId")
    suspend fun approve(conversationId: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnread(conversationId: String)

    @Query("UPDATE conversations SET needsSessionReset = :needsReset WHERE contactId = :contactId")
    suspend fun setNeedsSessionReset(contactId: String, needsReset: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

package com.chatcontroll.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chatcontroll.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET state = :state WHERE id = :messageId")
    suspend fun updateState(messageId: String, state: String)

    @Query("UPDATE messages SET state = :state, timestamp = :timestamp WHERE id = :messageId")
    suspend fun updateStateAndTimestamp(messageId: String, state: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE state = 'FAILED'")
    suspend fun getFailedMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE state = 'SENDING'")
    suspend fun getSendingMessages(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(conversationId: String, beforeTimestamp: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

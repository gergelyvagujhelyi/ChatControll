package com.chatcontroll.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chatcontroll.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun getByUserId(userId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    fun observeByUserId(userId: String): Flow<ContactEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

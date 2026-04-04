package com.chatcontroll.app.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.local.AppDatabase
import com.chatcontroll.app.data.local.dao.ContactDao
import com.chatcontroll.app.data.local.dao.ConversationDao
import com.chatcontroll.app.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DB_NAME = "chatcontroll.db"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
    ): AppDatabase {
        val dbKey = keyManager.getDatabaseKey()
        val passphrase = dbKey.joinToString("") { "%02x".format(it) }.toByteArray()

        val factory = SupportOpenHelperFactory(passphrase)

        return try {
            buildDatabase(context, factory).also {
                // Force open to detect SQLCipher errors early
                it.openHelper.writableDatabase
            }
        } catch (e: Exception) {
            Log.w("DatabaseModule", "Database unreadable, deleting and recreating", e)
            context.deleteDatabase(DB_NAME)
            databaseWasReset = true
            buildDatabase(context, factory)
        }
    }

    /** Set to true when the DB had to be deleted due to encryption key mismatch. */
    @Volatile
    var databaseWasReset: Boolean = false
        private set

    private fun buildDatabase(context: Context, factory: SupportOpenHelperFactory): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DB_NAME,
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()
}

package com.chatcontroll.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Schema migration from v1 to v2 — add any new columns/tables here
            // Using addColumn with defaults ensures existing data is preserved
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
    ): AppDatabase {
        val dbKey = keyManager.getDatabaseKey()
        val passphrase = dbKey.joinToString("") { "%02x".format(it) }.toByteArray()

        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "chatcontroll.db",
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()
}

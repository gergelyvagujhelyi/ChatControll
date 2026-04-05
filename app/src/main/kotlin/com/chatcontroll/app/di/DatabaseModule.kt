package com.chatcontroll.app.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.chatcontroll.app.BuildConfig
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
            if (BuildConfig.DEBUG) Log.w("DatabaseModule", "Database unreadable, deleting and recreating", e)
            context.deleteDatabase(DB_NAME)
            databaseWasReset = true
            buildDatabase(context, factory)
        }
    }

    /** Set to true when the DB had to be deleted due to encryption key mismatch. */
    @Volatile
    var databaseWasReset: Boolean = false
        private set

    /** v1→v2: add pqcEstablished and signatureRequired columns to contacts. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN pqcEstablished INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE contacts ADD COLUMN signatureRequired INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** v2→v3: add isApproved column to conversations for message requests. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN isApproved INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** v3→v4: add needsSessionReset column to conversations for key rotation flow. */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN needsSessionReset INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun buildDatabase(context: Context, factory: SupportOpenHelperFactory): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DB_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()
}

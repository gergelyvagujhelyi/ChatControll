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
        dbKey.fill(0)

        val factory = SupportOpenHelperFactory(passphrase)
        passphrase.fill(0)

        return try {
            buildDatabase(context, factory).also {
                // Force open to detect SQLCipher errors early
                it.openHelper.writableDatabase
            }
        } catch (e: Exception) {
            Log.e("DatabaseModule", "Database unreadable (key mismatch or corruption), deleting and recreating", e)
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

    /** v4→v5: add peerDeleted column to conversations for account deletion flow. */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN peerDeleted INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v6→v7: add pqcSigningKey column to contacts for ML-DSA-65 post-quantum authentication. */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("PRAGMA table_info(contacts)")
            var hasColumn = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "pqcSigningKey") {
                    hasColumn = true
                    break
                }
            }
            cursor.close()
            if (!hasColumn) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN pqcSigningKey BLOB NOT NULL DEFAULT x''")
            }
        }
    }

    /** v5→v6: add unique index on conversations.contactId to prevent duplicate rows per contact. */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Remove any duplicate contactId rows, keeping the one with the latest message.
            // SQLite guarantees non-aggregated columns come from the MAX() row.
            db.execSQL("""
                DELETE FROM conversations WHERE id NOT IN (
                    SELECT id FROM (
                        SELECT id, MAX(COALESCE(lastMessageTimestamp, 0))
                        FROM conversations
                        GROUP BY contactId
                    )
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_contactId ON conversations (contactId)")
        }
    }

    private fun buildDatabase(context: Context, factory: SupportOpenHelperFactory): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DB_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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

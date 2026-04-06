package com.chatcontroll.app.data.local.converter

import android.util.Base64
import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    @TypeConverter
    fun toByteArray(value: String): ByteArray {
        if (value.isEmpty()) return ByteArray(0)
        return try {
            Base64.decode(value, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Corrupted data in the database is a serious integrity issue —
            // throw so the caller can handle it rather than silently returning
            // an empty array that would cause downstream decryption failures.
            android.util.Log.e("Converters", "Corrupted Base64 in database column", e)
            throw IllegalStateException("Corrupted Base64 data in database", e)
        }
    }
}

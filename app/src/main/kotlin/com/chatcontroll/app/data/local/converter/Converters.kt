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
            // Log at ERROR — corrupted DB data is a serious integrity issue.
            // Return empty ByteArray rather than throwing, because Room
            // TypeConverters propagate exceptions as app crashes. Corrupted
            // rows are handled downstream (decrypt failures, tombstones).
            android.util.Log.e("Converters", "Corrupted Base64 in database column — returning empty", e)
            ByteArray(0)
        }
    }
}

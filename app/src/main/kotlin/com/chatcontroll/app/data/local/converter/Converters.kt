package com.chatcontroll.app.data.local.converter

import android.util.Base64
import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    @TypeConverter
    fun toByteArray(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        if (com.chatcontroll.app.BuildConfig.DEBUG) android.util.Log.e("Converters", "Invalid Base64 in database column", e)
        ByteArray(0)
    }
}

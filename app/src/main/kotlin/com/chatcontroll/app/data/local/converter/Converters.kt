package com.chatcontroll.app.data.local.converter

import android.util.Base64
import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    @TypeConverter
    fun toByteArray(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}

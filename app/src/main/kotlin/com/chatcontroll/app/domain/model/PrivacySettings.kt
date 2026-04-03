package com.chatcontroll.app.domain.model

data class PrivacySettings(
    val lockScreenPreview: LockScreenPreviewMode = LockScreenPreviewMode.HIDE_ALL,
    val disappearingMessagesDefault: DisappearingDuration = DisappearingDuration.OFF,
    val readReceipts: Boolean = false,
    val screenSecurity: Boolean = true,
    val localDataRetentionDays: Int = 90,
)

enum class LockScreenPreviewMode {
    SHOW_ALL,
    SENDER_ONLY,
    HIDE_BODY,
    HIDE_ALL,
}

enum class DisappearingDuration {
    OFF,
    MINUTES_5,
    HOURS_1,
    HOURS_24,
    DAYS_7,
    DAYS_30;

    val millis: Long?
        get() = when (this) {
            OFF -> null
            MINUTES_5 -> 5L * 60 * 1000
            HOURS_1 -> 60L * 60 * 1000
            HOURS_24 -> 24L * 60 * 60 * 1000
            DAYS_7 -> 7L * 24 * 60 * 60 * 1000
            DAYS_30 -> 30L * 24 * 60 * 60 * 1000
        }
}

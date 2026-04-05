package com.chatcontroll.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chatcontroll.app.domain.model.MessageState

@Composable
fun CallEventItem(
    state: MessageState,
    durationSeconds: Long,
    timestamp: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val isMissed = state == MessageState.CALL_MISSED
    val wasConnected = durationSeconds > 0

    val icon = when {
        isMissed -> Icons.AutoMirrored.Filled.CallMissed
        isOutgoing -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }

    val iconTint = if (isMissed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    val label = when {
        isMissed -> "Missed voice call"
        wasConnected -> "Voice call"
        isOutgoing -> "Outgoing call"
        else -> "Incoming call"
    }

    val durationText = if (wasConnected) {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    } else null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = buildString {
                append(" ")
                append(label)
                if (durationText != null) {
                    append(" \u00B7 ")
                    append(durationText)
                }
                append(" \u00B7 ")
                append(timestamp)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (isMissed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
        )
    }
}

package com.chatcontroll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chatcontroll.app.domain.model.MessageState

@Composable
fun MessageBubble(
    text: String,
    timestamp: String,
    isOutgoing: Boolean,
    state: MessageState,
    modifier: Modifier = Modifier,
) {
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOutgoing) 16.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 16.dp,
    )

    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    if (isOutgoing) {
                        MessageStateIcon(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStateIcon(state: MessageState) {
    val (icon, tint) = when (state) {
        MessageState.SENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        MessageState.SENT -> Icons.Default.Check to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        MessageState.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        MessageState.SEEN -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        MessageState.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(
        imageVector = icon,
        contentDescription = state.name.lowercase(),
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

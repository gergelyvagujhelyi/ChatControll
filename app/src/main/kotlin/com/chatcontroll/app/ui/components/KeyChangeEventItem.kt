package com.chatcontroll.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chatcontroll.app.domain.model.MessageState

@Composable
fun KeyChangeEventItem(
    state: MessageState,
    timestamp: String,
    modifier: Modifier = Modifier,
) {
    val isAccountDeleted = state == MessageState.ACCOUNT_DELETED
    val label = when (state) {
        MessageState.KEY_ROTATED_LOCAL -> "You rotated your keys"
        MessageState.KEY_ROTATED_REMOTE -> "Peer rotated their keys"
        MessageState.ACCOUNT_DELETED -> "Peer deleted their account"
        else -> "Keys changed"
    }
    val icon = if (isAccountDeleted) Icons.Default.PersonOff else Icons.Default.VpnKey
    val tint = if (isAccountDeleted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

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
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = " $label \u00B7 $timestamp",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

package com.chatcontroll.app.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatcontroll.app.ui.components.ConversationItem
import com.chatcontroll.app.ui.components.EmptyState
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (conversationId: String, contactId: String) -> Unit,
    onAddContact: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsState()
    val messageRequests by viewModel.messageRequests.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ChatControll") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddContact,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New conversation")
            }
        },
    ) { padding ->
        if (conversations.isEmpty() && messageRequests.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Chat,
                title = "No conversations yet",
                subtitle = "Tap + to add a contact and start a private conversation.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (messageRequests.isNotEmpty()) {
                    item {
                        Text(
                            text = "Message Requests",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(messageRequests, key = { "req_${it.id}" }) { request ->
                        MessageRequestItem(
                            displayName = request.contactDisplayName,
                            lastMessage = request.lastMessagePreview,
                            onAccept = { viewModel.acceptMessageRequest(request.id) },
                            onReject = { viewModel.rejectMessageRequest(request.id) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                        )
                    }
                    item {
                        HorizontalDivider(thickness = 1.dp)
                    }
                }
                items(conversations, key = { it.id }) { conversation ->
                    ConversationItem(
                        displayName = conversation.contactDisplayName,
                        lastMessage = conversation.lastMessagePreview,
                        timestamp = conversation.lastMessageTimestamp?.formatShort(),
                        unreadCount = conversation.unreadCount,
                        isEncrypted = conversation.isEncrypted,
                        onClick = {
                            onConversationClick(conversation.id, conversation.contactId)
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRequestItem(
    displayName: String,
    lastMessage: String?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium,
        )
        if (lastMessage != null) {
            Text(
                text = lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onAccept) {
                Text("Accept")
            }
            OutlinedButton(
                onClick = onReject,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Reject")
            }
        }
    }
}

private fun Instant.formatShort(): String {
    val tz = TimeZone.currentSystemDefault()
    val local = toLocalDateTime(tz)
    val now = kotlinx.datetime.Clock.System.now().toLocalDateTime(tz)
    return if (local.date == now.date) {
        "%02d:%02d".format(local.hour, local.minute)
    } else if (local.year == now.year) {
        "%02d %s".format(local.dayOfMonth, local.month.name.take(3).lowercase()
            .replaceFirstChar { it.uppercase() })
    } else {
        "%02d/%02d/%d".format(local.dayOfMonth, local.monthNumber, local.year)
    }
}

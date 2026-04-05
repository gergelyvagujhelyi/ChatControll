package com.chatcontroll.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatcontroll.app.domain.model.MessageState
import com.chatcontroll.app.ui.components.CallEventItem
import com.chatcontroll.app.ui.components.MessageBubble
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onCallClick: (String, String) -> Unit = { _, _ -> },
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val conversation by viewModel.conversation.collectAsState()
    val composerText by viewModel.composerText.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val isReEstablishing by viewModel.isReEstablishing.collectAsState()
    val encryptionInfo = viewModel.encryptionInfo
    val needsSessionReset = conversation?.needsSessionReset == true

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Show error snackbar
    LaunchedEffect(sendError) {
        sendError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = conversation?.contactDisplayName ?: "Chat",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (conversation?.isEncrypted == true) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = encryptionInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onCallClick(viewModel.contactId, conversation?.contactDisplayName ?: "Unknown") }) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Voice call",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Messages list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    val time = message.timestamp
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                    val timeStr = "%02d:%02d".format(time.hour, time.minute)

                    if (message.state.isCallEvent) {
                        val duration = message.plaintext.toLongOrNull() ?: 0L
                        CallEventItem(
                            state = message.state,
                            durationSeconds = duration,
                            timestamp = timeStr,
                            isOutgoing = message.isOutgoing,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    } else {
                        MessageBubble(
                            text = when {
                                message.state == MessageState.REJECTED -> "\u26D4 Message rejected (unsigned or unverifiable)"
                                message.state == MessageState.DECRYPT_FAILED -> "\u26A0 Could not decrypt this message"
                                message.plaintext.isEmpty() -> "..."
                                else -> message.plaintext
                            },
                            timestamp = timeStr,
                            isOutgoing = message.isOutgoing,
                            state = message.state,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            // Session reset banner
            if (needsSessionReset) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(enabled = !isReEstablishing) { viewModel.reEstablishSession() }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isReEstablishing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "Re-establishing secure session...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    } else {
                        Text(
                            text = "Peer rotated keys. Tap to re-establish session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Composer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = composerText,
                    onValueChange = viewModel::updateComposer,
                    modifier = Modifier.weight(1f),
                    enabled = !needsSessionReset,
                    placeholder = {
                        Text(if (needsSessionReset) "Session expired" else "Message")
                    },
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                    maxLines = 4,
                )

                IconButton(
                    onClick = viewModel::send,
                    enabled = composerText.isNotBlank() && !needsSessionReset,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (composerText.isNotBlank() && !needsSessionReset) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        },
                    )
                }
            }
        }
    }
}

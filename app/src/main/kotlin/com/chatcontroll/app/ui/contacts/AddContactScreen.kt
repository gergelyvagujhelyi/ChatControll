package com.chatcontroll.app.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onContactAdded: (conversationId: String, contactId: String) -> Unit,
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val shareCodeInput by viewModel.shareCodeInput.collectAsState()
    val state by viewModel.state.collectAsState()
    val myShareCode by viewModel.myShareCode.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(state) {
        if (state is AddContactState.Success) {
            val success = state as AddContactState.Success
            onContactAdded(success.conversationId, success.contact.userId)
            viewModel.resetState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Add Contact") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // My share code
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Your share code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = myShareCode ?: "Loading...",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    IconButton(
                        onClick = {
                            myShareCode?.let {
                                clipboardManager.setText(AnnotatedString(it))
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    Text(
                        text = "Share this code with others so they can message you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Add contact input
            Text(
                text = "Enter a share code",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = shareCodeInput,
                onValueChange = viewModel::updateShareCode,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste share code") },
                singleLine = true,
                isError = state is AddContactState.Error,
                supportingText = {
                    if (state is AddContactState.Error) {
                        Text(
                            text = (state as AddContactState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = viewModel::submitShareCode,
                modifier = Modifier.fillMaxWidth(),
                enabled = shareCodeInput.isNotBlank() && state !is AddContactState.Loading,
            ) {
                if (state is AddContactState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Add contact")
                }
            }

            // Existing contacts
            if (contacts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Contacts",
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn {
                    items(contacts, key = { it.userId }) { contact ->
                        Text(
                            text = contact.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

package com.chatcontroll.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatcontroll.app.domain.model.DisappearingDuration
import com.chatcontroll.app.domain.model.LockScreenPreviewMode
import com.chatcontroll.app.ui.components.QrCodeImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onWiped: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.privacySettings.collectAsState()
    val shareCode by viewModel.shareCode.collectAsState()
    val showWipeConfirmation by viewModel.showWipeConfirmation.collectAsState()
    val wipeCompleted by viewModel.wipeCompleted.collectAsState()

    LaunchedEffect(wipeCompleted) {
        if (wipeCompleted) onWiped()
    }

    if (showWipeConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelWipe,
            title = { Text("Wipe all local data?") },
            text = {
                Text(
                    "This will permanently delete your identity, all messages, " +
                        "contacts, and keys from this device. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWipe() }) {
                    Text("Wipe", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelWipe) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Identity section
            SectionHeader("Identity")
            SettingsItem(
                title = "Your share code",
                subtitle = shareCode ?: "Loading...",
            )
            if (shareCode != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCodeImage(content = shareCode!!)
                }
            }

            HorizontalDivider()

            // Notification privacy
            SectionHeader("Notification Privacy")

            val previewModes = LockScreenPreviewMode.entries
            val previewLabels = mapOf(
                LockScreenPreviewMode.SHOW_ALL to "Show sender and message",
                LockScreenPreviewMode.SENDER_ONLY to "Show sender only",
                LockScreenPreviewMode.HIDE_BODY to "Hide message body",
                LockScreenPreviewMode.HIDE_ALL to "No lock screen preview",
            )
            previewModes.forEach { mode ->
                SettingsRadioItem(
                    title = previewLabels[mode] ?: mode.name,
                    selected = settings.lockScreenPreview == mode,
                    onClick = { viewModel.updateLockScreenPreview(mode) },
                )
            }

            HorizontalDivider()

            // Privacy
            SectionHeader("Privacy")

            SettingsToggle(
                title = "Read receipts",
                subtitle = "Let others know when you've read their messages",
                checked = settings.readReceipts,
                onCheckedChange = viewModel::updateReadReceipts,
            )

            SettingsToggle(
                title = "Screen security",
                subtitle = "Block screenshots and app switcher preview",
                checked = settings.screenSecurity,
                onCheckedChange = viewModel::updateScreenSecurity,
            )

            HorizontalDivider()

            // Disappearing messages
            SectionHeader("Disappearing Messages")
            val durations = DisappearingDuration.entries
            val durationLabels = mapOf(
                DisappearingDuration.OFF to "Off",
                DisappearingDuration.MINUTES_5 to "5 minutes",
                DisappearingDuration.HOURS_1 to "1 hour",
                DisappearingDuration.HOURS_24 to "24 hours",
                DisappearingDuration.DAYS_7 to "7 days",
                DisappearingDuration.DAYS_30 to "30 days",
            )
            durations.forEach { duration ->
                SettingsRadioItem(
                    title = durationLabels[duration] ?: duration.name,
                    selected = settings.disappearingMessagesDefault == duration,
                    onClick = { viewModel.updateDisappearingMessages(duration) },
                )
            }

            HorizontalDivider()

            // Danger zone
            SectionHeader("Data")
            SettingsItem(
                title = "Wipe all local data",
                subtitle = "Permanently delete everything on this device",
                titleColor = MaterialTheme.colorScheme.error,
                onClick = viewModel::requestWipe,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRadioItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

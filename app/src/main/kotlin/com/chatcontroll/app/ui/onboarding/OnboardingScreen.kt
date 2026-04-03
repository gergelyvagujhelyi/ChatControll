package com.chatcontroll.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatcontroll.app.domain.model.KeyType

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        OnboardingState.Welcome -> WelcomeStep(
            onContinue = { viewModel.advanceToPrivacy() },
        )
        OnboardingState.PrivacyExplainer -> PrivacyStep(
            onContinue = { viewModel.advanceToKeySelection() },
        )
        OnboardingState.KeySelection -> KeySelectionStep(
            onSelected = { keyType -> viewModel.selectKeyType(keyType) },
        )
        OnboardingState.NotificationPermission -> NotificationStep(
            onContinue = { viewModel.createGuestIdentity() },
        )
        OnboardingState.CreatingIdentity -> LoadingStep()
        is OnboardingState.Complete -> {
            onComplete()
        }
        is OnboardingState.Error -> ErrorStep(
            message = current.message,
            onRetry = { viewModel.createGuestIdentity() },
        )
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    OnboardingPage(
        icon = Icons.Default.Shield,
        title = "ChatControll",
        body = "Private messaging with post-quantum security.\n\n" +
            "No email. No phone number. No tracking.\n" +
            "Your identity is a cryptographic key generated on this device.",
        buttonText = "Get started",
        onButtonClick = onContinue,
    )
}

@Composable
private fun PrivacyStep(onContinue: () -> Unit) {
    OnboardingPage(
        icon = Icons.Default.Lock,
        title = "Your privacy model",
        body = "Your identity lives on this device only. " +
            "Messages are encrypted before they leave your phone.\n\n" +
            "The relay server sees only encrypted envelopes — " +
            "it cannot read your messages.\n\n" +
            "Important: If you lose this device without a backup, " +
            "your identity and message history cannot be recovered.",
        buttonText = "I understand",
        onButtonClick = onContinue,
    )
}

@Composable
private fun KeySelectionStep(onSelected: (KeyType) -> Unit) {
    var selected by remember { mutableStateOf(KeyType.HYBRID_POST_QUANTUM) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Choose your key type",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This determines the cryptographic algorithms used for your identity.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(32.dp))

        KeyOptionCard(
            title = "Standard",
            description = "X25519 + Ed25519\n\nBattle-tested, fast, and trusted by millions.",
            tag = "Legacy",
            isSelected = selected == KeyType.CLASSICAL,
            onClick = { selected = KeyType.CLASSICAL },
        )

        Spacer(modifier = Modifier.height(12.dp))

        KeyOptionCard(
            title = "Post-Quantum",
            description = "ML-KEM-768 + X25519\n\nProtects against future quantum computers. Uses NIST-standardized algorithms alongside classical crypto as a safety net.",
            tag = "Recommended",
            isSelected = selected == KeyType.HYBRID_POST_QUANTUM,
            onClick = { selected = KeyType.HYBRID_POST_QUANTUM },
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onSelected(selected) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun KeyOptionCard(
    title: String,
    description: String,
    tag: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
            )
            Column(
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tag == "Recommended") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun NotificationStep(onContinue: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        onContinue()
    }

    OnboardingPage(
        icon = Icons.Default.Notifications,
        title = "Notifications",
        body = "Allow notifications to receive messages when the app is in the background.\n\n" +
            "Message previews on the lock screen are hidden by default. " +
            "You can change this in Settings.",
        buttonText = "Allow notifications",
        onButtonClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onContinue()
            }
        },
        secondaryButtonText = "Skip",
        onSecondaryClick = onContinue,
    )
}

@Composable
private fun LoadingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Generating your identity...",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ErrorStep(message: String, onRetry: () -> Unit) {
    OnboardingPage(
        icon = Icons.Default.Shield,
        title = "Something went wrong",
        body = message,
        buttonText = "Retry",
        onButtonClick = onRetry,
    )
}

@Composable
private fun OnboardingPage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onButtonClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(buttonText)
        }

        if (secondaryButtonText != null && onSecondaryClick != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSecondaryClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(secondaryButtonText)
            }
        }
    }
}

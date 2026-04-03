package com.chatcontroll.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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
            onContinue = { viewModel.advanceToNotifications() },
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

package com.chatcontroll.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatcontroll.app.domain.model.CallDirection
import com.chatcontroll.app.domain.model.CallStatus
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsState()
    val callError by viewModel.callError.collectAsState()
    val encryptionInfo by viewModel.encryptionInfo.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(callError) {
        callError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCallError()
        }
    }

    // Permission launcher — shared by outgoing auto-start and incoming Accept button.
    // The callback checks current state to decide what to do after grant.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val state = viewModel.callState.value
            if (state?.direction == CallDirection.INCOMING && state.status == CallStatus.RINGING) {
                // User pressed Accept → permission dialog → granted → accept the call
                viewModel.acceptCall()
            } else {
                viewModel.onMicPermissionGranted()
            }
        }
    }

    // For outgoing calls: request permission immediately so the call can start.
    // For incoming calls: skip — let the user see who's calling first.
    // The Accept button handles permission when they choose to answer.
    LaunchedEffect(Unit) {
        val state = viewModel.callState.value
        if (state?.direction == CallDirection.INCOMING) return@LaunchedEffect

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onMicPermissionGranted()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-navigate back when call ends
    LaunchedEffect(callState) {
        val state = callState
        if (state == null) {
            // Give a moment for state to be set (e.g. incoming call navigation)
            delay(500)
            if (viewModel.callState.value == null) {
                onCallEnded()
            }
        } else when (state.status) {
            CallStatus.ENDED, CallStatus.FAILED, CallStatus.NO_RELAY,
            CallStatus.REJECTED, CallStatus.BUSY, CallStatus.UNAVAILABLE -> {
                delay(1500)
                onCallEnded()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Peer info
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = callState?.peerDisplayName ?: "Unknown",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            if (callState?.isNewContact == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Unknown contact",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText(callState?.status, callState?.direction),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            // Call duration
            if (callState?.status == CallStatus.CONNECTED) {
                Spacer(modifier = Modifier.height(8.dp))
                CallDurationTimer(connectedAt = callState?.connectedAt ?: System.currentTimeMillis())
            }

            // Relay unavailable warning
            if (callState?.relayUnavailable == true && callState?.status != CallStatus.NO_RELAY) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Relay unavailable \u2014 call may not connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            // Encryption info
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = encryptionInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Controls
        when {
            callState?.status == CallStatus.RINGING && callState?.direction == CallDirection.INCOMING -> {
                // Incoming: Accept / Decline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    FilledIconButton(
                        onClick = viewModel::rejectCall,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Decline", modifier = Modifier.size(32.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.acceptCall()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF4CAF50),
                        ),
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Accept", modifier = Modifier.size(32.dp))
                    }
                }
            }
            callState?.status == CallStatus.CONNECTED -> {
                // In-call: Mute, Speaker, End
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(
                        onClick = viewModel::toggleMute,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Default.MicOff,
                            contentDescription = "Mute",
                            tint = if (callState?.isMuted == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = viewModel::hangup,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End call", modifier = Modifier.size(32.dp))
                    }
                    IconButton(
                        onClick = viewModel::toggleSpeaker,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Speaker",
                            tint = if (callState?.isSpeakerOn == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            else -> {
                // Ringing outgoing or connecting: just cancel
                FilledIconButton(
                    onClick = viewModel::hangup,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Cancel", modifier = Modifier.size(32.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
    } // Scaffold
}

@Composable
private fun CallDurationTimer(connectedAt: Long) {
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(connectedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAt) / 1000
            delay(1000)
        }
    }

    val minutes = elapsed / 60
    val seconds = elapsed % 60
    Text(
        text = "%d:%02d".format(minutes, seconds),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun statusText(status: CallStatus?, direction: CallDirection?): String = when (status) {
    CallStatus.RINGING -> if (direction == CallDirection.INCOMING) "Incoming call..." else "Ringing..."
    CallStatus.CONNECTING -> "Connecting..."
    CallStatus.CONNECTED -> "Connected"
    CallStatus.ENDED -> "Call ended"
    CallStatus.FAILED -> "Call failed"
    CallStatus.NO_RELAY -> "Call failed \u2014 relay unavailable"
    CallStatus.REJECTED -> "Call declined"
    CallStatus.BUSY -> "Busy"
    CallStatus.UNAVAILABLE -> "Contact unavailable"
    else -> ""
}

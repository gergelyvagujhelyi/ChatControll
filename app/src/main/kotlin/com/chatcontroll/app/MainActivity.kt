package com.chatcontroll.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.chatcontroll.app.di.DatabaseModule
import com.chatcontroll.app.call.CallManager
import com.chatcontroll.app.domain.model.CallDirection
import com.chatcontroll.app.domain.model.CallStatus
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.repository.SettingsRepository
import com.chatcontroll.app.notification.ChatNotificationManager
import com.chatcontroll.app.ui.navigation.ChatNavGraph
import com.chatcontroll.app.ui.navigation.Routes
import com.chatcontroll.app.ui.theme.ChatControllTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identityRepository: IdentityRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var callManager: CallManager

    private val _deepLinkIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        _deepLinkIntent.value = intent

        // Apply screen security synchronously before setContent to avoid
        // a visible frame before the flag takes effect
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Reactively apply/clear FLAG_SECURE whenever the setting changes
        lifecycleScope.launch {
            settingsRepository.getPrivacySettings().collect { settings ->
                if (settings.screenSecurity) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            ChatControllTheme {
                var showDbResetDialog by remember {
                    mutableStateOf(DatabaseModule.databaseWasReset)
                }

                if (showDbResetDialog) {
                    AlertDialog(
                        onDismissRequest = { /* must press OK */ },
                        title = { Text("Local data was reset") },
                        text = {
                            Text(
                                "The local database could not be opened and had to be recreated. " +
                                "Your messages stored on this device have been lost. " +
                                "Your identity and contacts are unaffected."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showDbResetDialog = false }) {
                                Text("OK")
                            }
                        },
                    )
                }

                var startDestination by remember { mutableStateOf<String?>(null) }

                val navController = rememberNavController()

                LaunchedEffect(Unit) {
                    startDestination = if (identityRepository.hasIdentity()) {
                        Routes.CONVERSATIONS
                    } else {
                        Routes.ONBOARDING
                    }
                }

                // Handle deep-link from notification — only after NavHost is composed
                val deepLinkIntent by _deepLinkIntent
                LaunchedEffect(deepLinkIntent, startDestination) {
                    if (startDestination != Routes.CONVERSATIONS) return@LaunchedEffect
                    val currentIntent = deepLinkIntent ?: return@LaunchedEffect
                    val conversationId = currentIntent.getStringExtra(
                        ChatNotificationManager.EXTRA_CONVERSATION_ID
                    )
                    val contactId = currentIntent.getStringExtra(
                        ChatNotificationManager.EXTRA_CONTACT_ID
                    )
                    if (conversationId != null && contactId != null) {
                        navController.navigate(Routes.chat(conversationId, contactId))
                    }
                    _deepLinkIntent.value = null
                }

                // Navigate to CallScreen when an incoming call arrives
                val incomingCall by callManager.callState.collectAsState()
                LaunchedEffect(incomingCall, startDestination) {
                    if (startDestination == null) return@LaunchedEffect
                    val call = incomingCall
                    if (call != null &&
                        call.direction == CallDirection.INCOMING &&
                        call.status == CallStatus.RINGING
                    ) {
                        navController.navigate(Routes.call(call.peerId, call.peerDisplayName)) {
                            launchSingleTop = true
                        }
                    }
                }

                startDestination?.let { dest ->
                    ChatNavGraph(
                        navController = navController,
                        startDestination = dest,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        _deepLinkIntent.value = intent
    }
}

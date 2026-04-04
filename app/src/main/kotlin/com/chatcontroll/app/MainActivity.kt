package com.chatcontroll.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.chatcontroll.app.call.CallManager
import com.chatcontroll.app.domain.model.CallDirection
import com.chatcontroll.app.domain.model.CallStatus
import com.chatcontroll.app.domain.model.PrivacySettings
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.repository.SettingsRepository
import com.chatcontroll.app.notification.ChatNotificationManager
import com.chatcontroll.app.ui.navigation.ChatNavGraph
import com.chatcontroll.app.ui.navigation.Routes
import com.chatcontroll.app.ui.theme.ChatControllTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identityRepository: IdentityRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var callManager: CallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Apply screen security by default
        lifecycleScope.launch {
            val settings = settingsRepository.getPrivacySettings().firstOrNull() ?: PrivacySettings()
            if (settings.screenSecurity) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }

        setContent {
            ChatControllTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }

                val navController = rememberNavController()

                LaunchedEffect(Unit) {
                    val dest = if (identityRepository.hasIdentity()) {
                        Routes.CONVERSATIONS
                    } else {
                        Routes.ONBOARDING
                    }
                    startDestination = dest

                    // Handle deep-link from notification (after startDestination is resolved)
                    if (dest == Routes.CONVERSATIONS) {
                        val conversationId = intent.getStringExtra(
                            ChatNotificationManager.EXTRA_CONVERSATION_ID
                        )
                        val contactId = intent.getStringExtra(
                            ChatNotificationManager.EXTRA_CONTACT_ID
                        )
                        if (conversationId != null && contactId != null) {
                            navController.navigate(Routes.chat(conversationId, contactId))
                        }
                    }
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
                        navController.navigate(Routes.call(call.peerId, call.peerDisplayName))
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
}

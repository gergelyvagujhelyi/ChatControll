package com.chatcontroll.app.ui.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.chatcontroll.app.ui.call.CallScreen
import com.chatcontroll.app.ui.chat.ChatScreen
import com.chatcontroll.app.ui.contacts.AddContactScreen
import com.chatcontroll.app.ui.conversations.ConversationsScreen
import com.chatcontroll.app.ui.onboarding.OnboardingScreen
import com.chatcontroll.app.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{conversationId}/{contactId}"
    const val ADD_CONTACT = "add_contact"
    const val SETTINGS = "settings"
    const val CALL = "call/{contactId}/{displayName}"

    fun chat(conversationId: String, contactId: String) =
        "chat/$conversationId/$contactId"

    fun call(contactId: String, displayName: String) = "call/$contactId/${android.net.Uri.encode(displayName)}"
}

@Composable
fun ChatNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.CONVERSATIONS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CONVERSATIONS) {
            // Intercept system back so it finishes the activity instead of
            // popping the start destination and leaving an empty NavHost.
            val activity = LocalContext.current as? Activity
            BackHandler { activity?.finish() }

            ConversationsScreen(
                onConversationClick = { conversationId, contactId ->
                    navController.navigate(Routes.chat(conversationId, contactId))
                },
                onAddContact = {
                    navController.navigate(Routes.ADD_CONTACT)
                },
                onSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType },
            ),
        ) {
            ChatScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.CONVERSATIONS) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onCallClick = { contactId, displayName ->
                    navController.navigate(Routes.call(contactId, displayName))
                },
            )
        }

        composable(
            route = Routes.CALL,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                navArgument("displayName") { type = NavType.StringType },
            ),
        ) {
            CallScreen(
                onCallEnded = { navController.popBackStack() },
            )
        }

        composable(Routes.ADD_CONTACT) {
            AddContactScreen(
                onBack = { navController.popBackStack() },
                onContactAdded = { conversationId, contactId ->
                    navController.navigate(Routes.chat(conversationId, contactId)) {
                        popUpTo(Routes.CONVERSATIONS)
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onWiped = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}

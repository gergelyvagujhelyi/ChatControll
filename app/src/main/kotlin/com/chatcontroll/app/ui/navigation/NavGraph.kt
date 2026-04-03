package com.chatcontroll.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
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

    fun chat(conversationId: String, contactId: String) =
        "chat/$conversationId/$contactId"
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
                onBack = { navController.popBackStack() },
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

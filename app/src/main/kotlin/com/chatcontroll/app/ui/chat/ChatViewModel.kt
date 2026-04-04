package com.chatcontroll.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatcontroll.app.domain.model.Conversation
import com.chatcontroll.app.domain.model.Message
import com.chatcontroll.app.domain.repository.ConversationRepository
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.usecase.SendMessageUseCase
import com.chatcontroll.app.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
    private val sendMessage: SendMessageUseCase,
) : ViewModel() {

    val conversationId: String = savedStateHandle["conversationId"] ?: ""
    val contactId: String = savedStateHandle["contactId"] ?: ""

    val messages: StateFlow<List<Message>> =
        messageRepository.getMessages(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversation: StateFlow<Conversation?> =
        conversationRepository.getConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val encryptionInfo: String = if (identityRepository.isPqcSession(contactId)) {
        "ML-KEM-768 + X25519 + AES-256-GCM"
    } else {
        "X25519 + AES-256-GCM"
    }

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    init {
        // Clear unread badge when conversation is opened
        viewModelScope.launch {
            conversationRepository.clearUnread(conversationId)
        }
        // Poll for new messages while the chat screen is open
        // (supplements WebSocket/FCM for reliability; kept infrequent to save battery)
        viewModelScope.launch {
            while (true) {
                try {
                    messageRepository.fetchPendingFromServer()
                } catch (_: Exception) { }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    fun updateComposer(text: String) {
        _composerText.value = text
    }

    fun send() {
        val text = _composerText.value.trim()
        if (text.isBlank()) return

        _composerText.value = ""
        _sendError.value = null

        viewModelScope.launch {
            sendMessage(conversationId, contactId, text)
                .onFailure { e ->
                    _sendError.value = e.message
                }
        }
    }

    fun retrySend(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.retryFailed(messageId)
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Retry failed"
            }
        }
    }

    fun clearError() {
        _sendError.value = null
    }
}

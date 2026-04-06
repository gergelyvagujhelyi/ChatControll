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
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    val encryptionInfo: StateFlow<String> =
        identityRepository.observePqcSession(contactId)
            .map { isPqc ->
                if (isPqc) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM"
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                if (identityRepository.isPqcSession(contactId)) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM",
            )

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private val _isReEstablishing = MutableStateFlow(false)
    val isReEstablishing: StateFlow<Boolean> = _isReEstablishing.asStateFlow()

    init {
        // Mark this conversation as active so incoming messages don't bump unread
        messageRepository.setActiveConversation(conversationId)
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
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Periodic message fetch failed: ${e.message}")
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        messageRepository.setActiveConversation(null)
    }

    fun updateComposer(text: String) {
        _composerText.value = text
    }

    fun send() {
        val text = _composerText.value.trim()
        if (text.isBlank()) return
        if (conversation.value?.peerDeleted == true) {
            _sendError.value = "This user has deleted their account."
            return
        }
        if (conversation.value?.needsSessionReset == true) {
            _sendError.value = "Peer rotated keys. Tap the banner above to resume sending."
            return
        }
        if (conversation.value?.isApproved == false) {
            _sendError.value = "Accept this message request before replying."
            return
        }

        _composerText.value = ""
        _sendError.value = null

        viewModelScope.launch {
            sendMessage(conversationId, contactId, text)
                .onFailure { e ->
                    _sendError.value = "Failed to send message"
                }
        }
    }

    fun reEstablishSession() {
        _isReEstablishing.value = true
        _sendError.value = null
        viewModelScope.launch {
            try {
                messageRepository.reEstablishSession(contactId)
            } catch (e: Exception) {
                _sendError.value = "Failed to re-establish session. Try again."
            } finally {
                _isReEstablishing.value = false
            }
        }
    }

    fun retrySend(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.retryFailed(messageId)
            } catch (e: Exception) {
                _sendError.value = "Retry failed"
            }
        }
    }

    fun acceptMessageRequest() {
        viewModelScope.launch {
            conversationRepository.approveConversation(conversationId)
        }
    }

    fun rejectMessageRequest(onRejected: () -> Unit) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
            onRejected()
        }
    }

    fun clearError() {
        _sendError.value = null
    }
}

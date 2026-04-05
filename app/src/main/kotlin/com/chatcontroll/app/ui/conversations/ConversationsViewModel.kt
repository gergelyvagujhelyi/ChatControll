package com.chatcontroll.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatcontroll.app.domain.model.Conversation
import com.chatcontroll.app.domain.repository.ConversationRepository
import com.chatcontroll.app.domain.repository.IdentityRepository
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
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        conversationRepository.getConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messageRequests: StateFlow<List<Conversation>> =
        conversationRepository.getMessageRequests()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _shareCode.value = identity?.shareCode
        }
        // Fetch once when the screen opens; ongoing delivery is handled
        // by WebSocket, FCM push, and the periodic SyncWorker.
        viewModelScope.launch {
            try {
                messageRepository.fetchPendingFromServer()
            } catch (_: Exception) { }
        }
    }

    fun acceptMessageRequest(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.approveConversation(conversationId)
        }
    }

    fun rejectMessageRequest(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
        }
    }
}

package com.chatcontroll.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatcontroll.app.domain.model.Contact
import com.chatcontroll.app.domain.repository.ConversationRepository
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.usecase.AddContactUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val addContact: AddContactUseCase,
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    private val _shareCodeInput = MutableStateFlow("")
    val shareCodeInput: StateFlow<String> = _shareCodeInput.asStateFlow()

    private val _state = MutableStateFlow<AddContactState>(AddContactState.Idle)
    val state: StateFlow<AddContactState> = _state.asStateFlow()

    private val _myShareCode = MutableStateFlow<String?>(null)
    val myShareCode: StateFlow<String?> = _myShareCode.asStateFlow()

    val contacts = identityRepository.getContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _myShareCode.value = identity?.shareCode
        }
    }

    fun updateShareCode(code: String) {
        _shareCodeInput.value = code
    }

    fun submitShareCode() {
        val code = _shareCodeInput.value.trim()
        if (code.isBlank()) return

        _state.value = AddContactState.Loading
        viewModelScope.launch {
            addContact(code)
                .onSuccess { contact ->
                    val conversationId = conversationRepository.getOrCreateConversation(contact.userId)
                    _state.value = AddContactState.Success(contact, conversationId)
                    _shareCodeInput.value = ""
                }
                .onFailure { e ->
                    _state.value = AddContactState.Error("Failed to add contact. Check the share code and try again.")
                }
        }
    }

    fun resetState() {
        _state.value = AddContactState.Idle
    }
}

sealed interface AddContactState {
    data object Idle : AddContactState
    data object Loading : AddContactState
    data class Success(val contact: Contact, val conversationId: String) : AddContactState
    data class Error(val message: String) : AddContactState
}

package com.chatcontroll.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatcontroll.app.domain.model.Identity
import com.chatcontroll.app.domain.usecase.CreateIdentityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val createIdentity: CreateIdentityUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Welcome)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun advanceToPrivacy() {
        _state.value = OnboardingState.PrivacyExplainer
    }

    fun advanceToNotifications() {
        _state.value = OnboardingState.NotificationPermission
    }

    fun createGuestIdentity() {
        _state.value = OnboardingState.CreatingIdentity
        viewModelScope.launch {
            try {
                val identity = createIdentity()
                _state.value = OnboardingState.Complete(identity)
            } catch (e: Exception) {
                _state.value = OnboardingState.Error(e.message ?: "Failed to create identity")
            }
        }
    }
}

sealed interface OnboardingState {
    data object Welcome : OnboardingState
    data object PrivacyExplainer : OnboardingState
    data object NotificationPermission : OnboardingState
    data object CreatingIdentity : OnboardingState
    data class Complete(val identity: Identity) : OnboardingState
    data class Error(val message: String) : OnboardingState
}

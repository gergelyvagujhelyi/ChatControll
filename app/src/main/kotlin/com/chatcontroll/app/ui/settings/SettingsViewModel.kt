package com.chatcontroll.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatcontroll.app.domain.model.DisappearingDuration
import com.chatcontroll.app.domain.model.LockScreenPreviewMode
import com.chatcontroll.app.domain.model.PrivacySettings
import com.chatcontroll.app.domain.repository.IdentityRepository
import com.chatcontroll.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    val privacySettings: StateFlow<PrivacySettings> =
        settingsRepository.getPrivacySettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PrivacySettings())

    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    private val _showWipeConfirmation = MutableStateFlow(false)
    val showWipeConfirmation: StateFlow<Boolean> = _showWipeConfirmation.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _shareCode.value = identity?.shareCode
        }
    }

    fun updateLockScreenPreview(mode: LockScreenPreviewMode) {
        viewModelScope.launch {
            settingsRepository.updatePrivacySettings(
                privacySettings.value.copy(lockScreenPreview = mode),
            )
        }
    }

    fun updateReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updatePrivacySettings(
                privacySettings.value.copy(readReceipts = enabled),
            )
        }
    }

    fun updateScreenSecurity(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updatePrivacySettings(
                privacySettings.value.copy(screenSecurity = enabled),
            )
        }
    }

    fun updateDisappearingMessages(duration: DisappearingDuration) {
        viewModelScope.launch {
            settingsRepository.updatePrivacySettings(
                privacySettings.value.copy(disappearingMessagesDefault = duration),
            )
        }
    }

    fun requestWipe() {
        _showWipeConfirmation.value = true
    }

    fun cancelWipe() {
        _showWipeConfirmation.value = false
    }

    fun confirmWipe() {
        viewModelScope.launch {
            settingsRepository.wipeLocalData()
            _showWipeConfirmation.value = false
        }
    }
}

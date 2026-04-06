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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val settingsMutex = Mutex()

    private val _showWipeConfirmation = MutableStateFlow(false)
    val showWipeConfirmation: StateFlow<Boolean> = _showWipeConfirmation.asStateFlow()

    private val _wipeCompleted = MutableStateFlow(false)
    val wipeCompleted: StateFlow<Boolean> = _wipeCompleted.asStateFlow()

    private val _wipeError = MutableStateFlow<String?>(null)
    val wipeError: StateFlow<String?> = _wipeError.asStateFlow()

    private val _showRotateConfirmation = MutableStateFlow(false)
    val showRotateConfirmation: StateFlow<Boolean> = _showRotateConfirmation.asStateFlow()

    private val _isRotatingKeys = MutableStateFlow(false)
    val isRotatingKeys: StateFlow<Boolean> = _isRotatingKeys.asStateFlow()

    private val _rotationError = MutableStateFlow<String?>(null)
    val rotationError: StateFlow<String?> = _rotationError.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _shareCode.value = identity?.shareCode
        }
    }

    fun updateLockScreenPreview(mode: LockScreenPreviewMode) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(lockScreenPreview = mode),
                )
            }
        }
    }

    fun updateReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(readReceipts = enabled),
                )
            }
        }
    }

    fun updateScreenSecurity(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(screenSecurity = enabled),
                )
            }
        }
    }

    fun updateDisappearingMessages(duration: DisappearingDuration) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(disappearingMessagesDefault = duration),
                )
            }
        }
    }

    fun requestWipe() {
        _showWipeConfirmation.value = true
    }

    fun cancelWipe() {
        _showWipeConfirmation.value = false
    }

    fun confirmWipe() {
        _showWipeConfirmation.value = false
        performWipe { settingsRepository.wipeLocalData() }
    }

    fun retryWipe() {
        _wipeError.value = null
        performWipe { settingsRepository.retryServerDeletion() }
    }

    fun forceWipeLocal() {
        _wipeError.value = null
        viewModelScope.launch {
            settingsRepository.wipeLocalOnly()
            _wipeCompleted.value = true
        }
    }

    fun dismissWipeError() {
        _wipeError.value = null
        _wipeCompleted.value = true
    }

    private fun performWipe(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                _wipeCompleted.value = true
            } catch (_: Exception) {
                _wipeError.value = "We could not reach the server to delete your account. " +
                    "Your contacts may still try to send you messages.\n\n" +
                    "Please check your internet connection and tap Retry, " +
                    "or tap Delete Anyway to continue without server notification."
            }
        }
    }

    fun requestRotateKeys() {
        _rotationError.value = null
        _showRotateConfirmation.value = true
    }

    fun cancelRotateKeys() {
        _showRotateConfirmation.value = false
    }

    fun confirmRotateKeys() {
        _showRotateConfirmation.value = false
        _isRotatingKeys.value = true
        _rotationError.value = null
        viewModelScope.launch {
            try {
                identityRepository.rotateIdentityKeys()
                // Refresh the displayed share code
                val identity = identityRepository.getIdentity()
                _shareCode.value = identity?.shareCode
            } catch (e: Exception) {
                android.util.Log.e("SettingsVM", "Key rotation failed", e)
                _rotationError.value = when (e) {
                    is java.io.IOException -> "Network error — check your connection and try again."
                    is IllegalStateException -> "Key rotation failed — local key error."
                    else -> "Key rotation failed. Please try again."
                }
            } finally {
                _isRotatingKeys.value = false
            }
        }
    }

    fun dismissRotationError() {
        _rotationError.value = null
    }
}

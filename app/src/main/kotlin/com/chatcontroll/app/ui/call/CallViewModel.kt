package com.chatcontroll.app.ui.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.chatcontroll.app.call.CallManager
import com.chatcontroll.app.domain.model.CallState
import com.chatcontroll.app.domain.repository.IdentityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val callManager: CallManager,
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    val callState: StateFlow<CallState?> = callManager.callState
    val callError: StateFlow<String?> = callManager.callError

    private val pendingContactId: String = savedStateHandle["contactId"] ?: ""
    private val pendingDisplayName: String = savedStateHandle.get<String>("displayName")
        ?.let { Uri.decode(it) } ?: pendingContactId.take(8)

    val encryptionInfo: StateFlow<String> =
        identityRepository.observePqcSession(pendingContactId)
            .map { isPqc ->
                if (isPqc) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM"
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                if (identityRepository.isPqcSession(pendingContactId)) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM",
            )

    /** Whether the outgoing call has been initiated (guards against double-start). */
    private var outgoingStarted = false

    /**
     * Called by the UI once microphone permission is confirmed.
     * Initiates an outgoing call if no call is active, or accepts an incoming one.
     */
    fun onMicPermissionGranted() {
        val current = callManager.callState.value
        if (current == null && !outgoingStarted && pendingContactId.isNotEmpty()) {
            outgoingStarted = true
            callManager.initiateCall(pendingContactId, pendingDisplayName)
        }
    }

    fun acceptCall() {
        callManager.acceptCall()
    }
    fun rejectCall() = callManager.rejectCall()
    fun hangup() = callManager.hangup()
    fun toggleMute() = callManager.toggleMute()
    fun toggleSpeaker() = callManager.toggleSpeaker()
    fun clearCallError() = callManager.clearCallError()
}

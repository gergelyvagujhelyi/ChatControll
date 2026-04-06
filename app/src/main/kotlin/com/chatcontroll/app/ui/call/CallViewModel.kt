package com.chatcontroll.app.ui.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import android.net.Uri
import com.chatcontroll.app.call.CallManager
import com.chatcontroll.app.domain.model.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val callManager: CallManager,
) : ViewModel() {

    val callState: StateFlow<CallState?> = callManager.callState
    val callError: StateFlow<String?> = callManager.callError

    private val pendingContactId: String = savedStateHandle["contactId"] ?: ""
    private val pendingDisplayName: String = savedStateHandle.get<String>("displayName")
        ?.let { Uri.decode(it) } ?: pendingContactId.take(8)

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

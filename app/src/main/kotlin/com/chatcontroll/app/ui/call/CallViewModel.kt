package com.chatcontroll.app.ui.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.chatcontroll.app.call.CallManager
import com.chatcontroll.app.domain.model.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val callManager: CallManager,
) : ViewModel() {

    val callState: StateFlow<CallState?> = callManager.callState

    init {
        // If no active call, this is an outgoing call — initiate it
        if (callManager.callState.value == null) {
            val contactId: String = savedStateHandle["contactId"] ?: ""
            val rawName: String? = savedStateHandle["displayName"]
            val displayName = rawName?.let { URLDecoder.decode(it, "UTF-8") } ?: contactId.take(8)
            if (contactId.isNotEmpty()) {
                callManager.initiateCall(contactId, displayName)
            }
        }
    }

    fun acceptCall() = callManager.acceptCall()
    fun rejectCall() = callManager.rejectCall()
    fun hangup() = callManager.hangup()
    fun toggleMute() = callManager.toggleMute()
    fun toggleSpeaker() = callManager.toggleSpeaker()
}

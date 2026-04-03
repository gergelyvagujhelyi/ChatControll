package com.chatcontroll.app.call

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class WebRtcEngine(context: Context) {

    private val eglBase = EglBase.create()

    private val factory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null

    init {
        initOnce(context)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                this@WebRtcEngine.onIceCandidate?.invoke(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state: $state")
                onConnectionStateChange?.invoke(state)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        // Add audio track
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        peerConnection?.addTrack(localAudioTrack)
    }

    suspend fun createOffer(): String = suspendCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { cont.resume(sdp.description) }
                    override fun onSetFailure(error: String?) { cont.resumeWithException(Exception(error)) }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { cont.resumeWithException(Exception(error)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    suspend fun handleRemoteOffer(sdp: String): String = suspendCoroutine { cont ->
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { cont.resume(answerSdp.description) }
                            override fun onSetFailure(error: String?) { cont.resumeWithException(Exception(error)) }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, answerSdp)
                    }
                    override fun onCreateFailure(error: String?) { cont.resumeWithException(Exception(error)) }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onSetFailure(error: String?) { cont.resumeWithException(Exception(error)) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteSdp)
    }

    suspend fun handleRemoteAnswer(sdp: String): Unit = suspendCoroutine { cont ->
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(error: String?) { cont.resumeWithException(Exception(error)) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteSdp)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun dispose() {
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.dispose()
        peerConnection = null
        localAudioTrack = null
        audioSource = null
    }

    fun shutdown() {
        dispose()
        factory.dispose()
        eglBase.release()
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        @Volatile private var initialized = false

        private fun initOnce(context: Context) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        PeerConnectionFactory.initialize(
                            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                                .createInitializationOptions()
                        )
                        initialized = true
                    }
                }
            }
        }

        private val DEFAULT_ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
    }
}

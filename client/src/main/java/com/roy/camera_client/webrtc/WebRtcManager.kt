package com.roy.camera_client.webrtc

import android.content.Context
import android.util.Log
import com.roy.camera_test.ICameraBroker
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * WebRTC Host Manager
 * - Streams video (from SharedMemory) and audio
 * - Sends notifications via DataChannel
 * - Receives commands from remote clients
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "video_track"
        private const val AUDIO_TRACK_ID = "audio_track"
        private const val STREAM_ID = "media_stream"
        private const val DATA_CHANNEL_LABEL = "control"
        private const val DATA_CHANNEL_ID = 0
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    // Video
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: SharedMemoryVideoCapturer? = null

    // Audio
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // DataChannel
    private var dataChannel: DataChannel? = null

    // Callbacks
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onLocalDescription: ((SessionDescription) -> Unit)? = null
    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null
    var onDataChannelStateChange: ((DataChannel.State) -> Unit)? = null

    /**
     * Initialize WebRTC components.
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory created")
    }

    /**
     * Start video capture from SharedMemory.
     */
    fun startVideoCapture(broker: ICameraBroker, sharedMemoryBuffer: ByteBuffer) {
        Log.d(TAG, "Starting video capture")

        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }

        videoSource = factory.createVideoSource(false)

        videoCapturer = SharedMemoryVideoCapturer(broker, sharedMemoryBuffer).apply {
            initialize(null, context, videoSource!!.capturerObserver)
            startCapture(640, 480, 30)
        }

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).apply {
            setEnabled(true)
        }

        Log.d(TAG, "Video capture started")
    }

    /**
     * Start audio capture from microphone.
     */
    fun startAudioCapture() {
        Log.d(TAG, "Starting audio capture")

        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }

        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
        }

        Log.d(TAG, "Audio capture started")
    }

    /**
     * Stop all captures.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping captures")

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.setEnabled(false)
        localVideoTrack?.dispose()
        localVideoTrack = null

        videoSource?.dispose()
        videoSource = null

        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null

        audioSource?.dispose()
        audioSource = null
    }

    /**
     * Set audio enabled/disabled (mute).
     */
    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Audio enabled: $enabled")
    }

    /**
     * Set video enabled/disabled.
     */
    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        Log.d(TAG, "Video enabled: $enabled")
    }

    /**
     * Add local video renderer for preview.
     */
    fun addLocalRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase!!.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
        localVideoTrack?.addSink(renderer)
        Log.d(TAG, "Local renderer added")
    }

    /**
     * Remove local video renderer.
     */
    fun removeLocalRenderer(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
        renderer.release()
    }

    /**
     * Create PeerConnection for P2P communication.
     */
    fun createPeerConnection(iceServers: List<PeerConnection.IceServer> = WebRtcConfig.getIceServers()) {
        Log.d(TAG, "Creating PeerConnection")

        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, createPeerConnectionObserver())

        // Add video track
        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf(STREAM_ID))
            Log.d(TAG, "Video track added")
        }

        // Add audio track
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf(STREAM_ID))
            Log.d(TAG, "Audio track added")
        }

        // Create DataChannel for commands/notifications (negotiated)
        ensureDataChannel()
    }

    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state: $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let { onIceCandidate?.invoke(it) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: DataChannel?) {
            Log.d(TAG, "Remote DataChannel received: ${channel?.label()}")
            // Handle incoming data channel from remote peer
            channel?.registerObserver(createDataChannelObserver())
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Log.d(TAG, "Connection state: $newState")
            newState?.let { onConnectionStateChange?.invoke(it) }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private fun ensureDataChannel() {
        val pc = peerConnection ?: return
        if (dataChannel != null) return

        val init = DataChannel.Init().apply {
            ordered = true
            negotiated = true
            id = DATA_CHANNEL_ID
        }

        val dc = pc.createDataChannel(DATA_CHANNEL_LABEL, init)
        dataChannel = dc
        dc.registerObserver(createDataChannelObserver())
        Log.d(TAG, "DataChannel created (negotiated): label=${dc.label()} id=${dc.id()} state=${dc.state()}")
    }

    private fun createDataChannelObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}

        override fun onStateChange() {
            val state = dataChannel?.state()
            Log.d(TAG, "DataChannel state: $state")
            state?.let { onDataChannelStateChange?.invoke(it) }
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            buffer?.let {
                val data = ByteArray(it.data.remaining())
                it.data.get(data)
                val message = String(data, StandardCharsets.UTF_8)
                Log.d(TAG, "Command received: $message")
                onCommandReceived?.invoke(message)
            }
        }
    }

    /**
     * Send notification/message to remote client.
     */
    fun sendNotification(message: String): Boolean {
        val channel = dataChannel
        if (channel == null || channel.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "DataChannel not open, cannot send: $message")
            return false
        }

        val data = message.toByteArray(StandardCharsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
        val sent = channel.send(buffer)
        Log.d(TAG, "Notification sent: $message, success: $sent")
        return sent
    }

    /**
     * Create and set local offer (as Host).
     */
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            onLocalDescription?.invoke(it)
                        }
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Set remote description (answer from client).
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set")
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
        }, sdp)
    }

    /**
     * Add ICE candidate from remote peer.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Close peer connection.
     */
    fun closePeerConnection() {
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    /**
     * Release all resources.
     */
    fun release() {
        Log.d(TAG, "Releasing WebRTC resources")

        stopCapture()
        closePeerConnection()

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null
    }

    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun isConnected(): Boolean {
        return peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
    }

    fun isDataChannelOpen(): Boolean {
        return dataChannel?.state() == DataChannel.State.OPEN
    }
}

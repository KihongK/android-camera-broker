package com.roy.camera_client.webrtc

import android.content.Context
import android.util.Log
import com.roy.camera_test.ICameraBroker
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Manages WebRTC peer connection and video streaming.
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "shared_memory_video"
        private const val STREAM_ID = "shared_memory_stream"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: SharedMemoryVideoCapturer? = null
    private var eglBase: EglBase? = null

    // Callbacks for signaling
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onLocalDescription: ((SessionDescription) -> Unit)? = null
    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    /**
     * Initialize WebRTC components.
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        eglBase = EglBase.create()

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory created")
    }

    /**
     * Start capturing video from SharedMemory.
     */
    fun startCapture(broker: ICameraBroker, sharedMemoryBuffer: ByteBuffer) {
        Log.d(TAG, "Starting video capture from SharedMemory")

        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }

        // Create video source
        videoSource = factory.createVideoSource(false)

        // Create custom capturer
        videoCapturer = SharedMemoryVideoCapturer(broker, sharedMemoryBuffer).apply {
            initialize(null, context, videoSource!!.capturerObserver)
            startCapture(640, 480, 30)
        }

        // Create video track
        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).apply {
            setEnabled(true)
        }

        Log.d(TAG, "Video capture started")
    }

    /**
     * Stop video capture.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping video capture")

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.setEnabled(false)
        localVideoTrack?.dispose()
        localVideoTrack = null

        videoSource?.dispose()
        videoSource = null
    }

    /**
     * Add local video renderer (for preview).
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
    fun createPeerConnection(iceServers: List<PeerConnection.IceServer> = getDefaultIceServers()) {
        Log.d(TAG, "Creating PeerConnection")

        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE connection receiving: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "ICE candidate: ${candidate?.sdp}")
                candidate?.let { onIceCandidate?.invoke(it) }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Stream added: ${stream?.id}")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "Stream removed: ${stream?.id}")
            }

            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "Data channel: ${channel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Track added")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state: $newState")
                newState?.let { onConnectionStateChange?.invoke(it) }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d(TAG, "Track received: ${transceiver?.mediaType}")
            }
        })

        // Add local video track to peer connection
        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf(STREAM_ID))
            Log.d(TAG, "Local video track added to PeerConnection")
        }
    }

    /**
     * Create and set local offer.
     */
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                Log.d(TAG, "Offer created: ${sdp?.type}")
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
     * Set remote description (answer from peer).
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
     * Get EGL context for renderer initialization.
     */
    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * Release all resources.
     */
    fun release() {
        Log.d(TAG, "Releasing WebRTC resources")

        stopCapture()

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null
    }

    private fun getDefaultIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }
}

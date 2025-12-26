package com.roy.camera_client.webrtc

import org.webrtc.PeerConnection

/**
 * WebRTC configuration constants.
 */
object WebRtcConfig {
    // Signaling server
    const val SIGNALING_SERVER_URL = "http://35.208.216.42:3000"

    // ICE servers
    const val STUN_URL = "stun:stun.l.google.com:19302"
    const val TURN_URL = "turn:35.208.216.42:3478"
    const val TURN_USERNAME = "webrtc"
    const val TURN_PASSWORD = "webrtc1234"

    // Default room name
    const val DEFAULT_ROOM_NAME = "alphacode_test2"

    /**
     * Get configured ICE servers.
     */
    fun getIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            // STUN server
            PeerConnection.IceServer.builder(STUN_URL)
                .createIceServer(),
            // TURN server
            PeerConnection.IceServer.builder(TURN_URL)
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
                .createIceServer()
        )
    }
}

package com.roy.camera_client.webrtc

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Socket.IO-based signaling client for WebRTC.
 */
class SignalingClient(
    private val serverUrl: String = WebRtcConfig.SIGNALING_SERVER_URL
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    private var socket: Socket? = null
    private var roomId: String = ""
    private var currentRemoteSocketId: String? = null

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onRoomJoined: ((String) -> Unit)? = null
    var onPeerJoined: ((String) -> Unit)? = null
    var onPeerLeft: ((String) -> Unit)? = null
    var onOfferReceived: ((SessionDescription, String) -> Unit)? = null
    var onAnswerReceived: ((SessionDescription, String) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate, String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onCallReady: ((String) -> Unit)? = null

    /**
     * Connect to signaling server and join room.
     */
    fun joinRoom(room: String, asHost: Boolean = true) {
        roomId = room

        try {
            val options = IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .build()

            socket = IO.socket(serverUrl, options)
            setupSocketListeners()
            socket?.connect()

            Log.d(TAG, "Connecting to: $serverUrl, room: $room")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket", e)
            onError?.invoke(e.message ?: "Connection failed")
        }
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Connected to signaling server")
            onConnected?.invoke()

            // Join room
            val joinData = JSONObject().apply {
                put("roomId", roomId)
            }
            socket?.emit("join-room", joinData)
        }

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
            Log.e(TAG, "Connection error: $error")
            onError?.invoke(error)
        }

        socket?.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = if (args.isNotEmpty()) args[0].toString() else "Disconnected"
            Log.d(TAG, "Disconnected: $reason")
            onDisconnected?.invoke(reason)
        }

        socket?.on("room-participants") { args ->
            try {
                val data = args[0] as JSONObject
                val participants = data.getJSONArray("participants")
                Log.d(TAG, "Room participants: ${participants.length()}")

                onRoomJoined?.invoke(roomId)

                if (participants.length() > 0) {
                    val firstPeer = participants.getJSONObject(0)
                    val socketId = firstPeer.getString("socketId")
                    currentRemoteSocketId = socketId
                    Log.d(TAG, "Call ready with peer: $socketId")
                    onCallReady?.invoke(socketId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse room-participants", e)
            }
        }

        socket?.on("user-joined") { args ->
            try {
                val data = args[0] as JSONObject
                val user = data.getJSONObject("user")
                val socketId = user.getString("socketId")
                currentRemoteSocketId = socketId
                Log.d(TAG, "User joined: $socketId")
                onPeerJoined?.invoke(socketId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse user-joined", e)
            }
        }

        socket?.on("user-left") {
            Log.d(TAG, "User left")
            currentRemoteSocketId = null
            onPeerLeft?.invoke("")
        }

        socket?.on("offer") { args ->
            try {
                val data = args[0] as JSONObject
                val offer = data.getJSONObject("offer")
                val senderSocketId = data.getString("senderSocketId")
                currentRemoteSocketId = senderSocketId

                val sdp = SessionDescription(
                    SessionDescription.Type.OFFER,
                    offer.getString("sdp")
                )
                Log.d(TAG, "Offer received from: $senderSocketId")
                onOfferReceived?.invoke(sdp, senderSocketId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse offer", e)
            }
        }

        socket?.on("answer") { args ->
            try {
                val data = args[0] as JSONObject
                val answer = data.getJSONObject("answer")
                val senderSocketId = data.getString("senderSocketId")
                currentRemoteSocketId = senderSocketId

                val sdp = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    answer.getString("sdp")
                )
                Log.d(TAG, "Answer received from: $senderSocketId")
                onAnswerReceived?.invoke(sdp, senderSocketId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse answer", e)
            }
        }

        socket?.on("ice-candidate") { args ->
            try {
                val data = args[0] as JSONObject
                val candidate = data.getJSONObject("candidate")
                val senderSocketId = data.getString("senderSocketId")
                currentRemoteSocketId = senderSocketId

                val iceCandidate = IceCandidate(
                    candidate.getString("sdpMid"),
                    candidate.getInt("sdpMLineIndex"),
                    candidate.getString("candidate")
                )
                Log.d(TAG, "ICE candidate received from: $senderSocketId")
                onIceCandidateReceived?.invoke(iceCandidate, senderSocketId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ice-candidate", e)
            }
        }
    }

    /**
     * Leave room and disconnect.
     */
    fun leaveRoom() {
        socket?.disconnect()
        socket?.off()
        socket = null
        currentRemoteSocketId = null
    }

    /**
     * Send SDP offer to peer.
     */
    fun sendOffer(sdp: SessionDescription, peerId: String? = null) {
        val targetId = peerId ?: currentRemoteSocketId ?: return

        val offer = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }

        val data = JSONObject().apply {
            put("offer", offer)
            put("targetSocketId", targetId)
        }

        socket?.emit("offer", data)
        Log.d(TAG, "Offer sent to: $targetId")
    }

    /**
     * Send SDP answer to peer.
     */
    fun sendAnswer(sdp: SessionDescription, peerId: String) {
        val answer = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }

        val data = JSONObject().apply {
            put("answer", answer)
            put("targetSocketId", peerId)
        }

        socket?.emit("answer", data)
        Log.d(TAG, "Answer sent to: $peerId")
    }

    /**
     * Send ICE candidate to peer.
     */
    fun sendIceCandidate(candidate: IceCandidate, peerId: String? = null) {
        val targetId = peerId ?: currentRemoteSocketId ?: return

        val candidateData = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }

        val data = JSONObject().apply {
            put("candidate", candidateData)
            put("targetSocketId", targetId)
        }

        socket?.emit("ice-candidate", data)
        Log.d(TAG, "ICE candidate sent to: $targetId")
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun getCurrentPeerId(): String? = currentRemoteSocketId
}

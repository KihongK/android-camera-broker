package com.roy.camera_client

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.roy.camera_client.ui.theme.CameraClientTheme
import com.roy.camera_client.webrtc.SignalingClient
import com.roy.camera_client.webrtc.WebRtcConfig
import com.roy.camera_client.webrtc.WebRtcManager
import com.roy.camera_test.ICameraBroker
import org.webrtc.SurfaceViewRenderer
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WebRtcHost"
        private const val BROKER_PACKAGE = "com.roy.camera_test"
        private const val BROKER_ACTION = "com.roy.camera_test.CAMERA_BROKER"
    }

    // Camera Broker IPC
    private var cameraBroker: ICameraBroker? = null
    private var isBound = false
    private var sharedMemory: android.os.SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    // WebRTC
    private var webRtcManager: WebRtcManager? = null
    private var signalingClient: SignalingClient? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null

    // UI State
    private val _brokerState = mutableStateOf("Disconnected")
    private val _signalingState = mutableStateOf("Disconnected")
    private val _webRtcState = mutableStateOf("Not initialized")
    private val _dataChannelState = mutableStateOf("Closed")
    private val _roomName = mutableStateOf(WebRtcConfig.DEFAULT_ROOM_NAME)
    private val _isStreaming = mutableStateOf(false)
    private val _isMuted = mutableStateOf(false)
    private val _commandLog = mutableStateListOf<String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Broker connected")
            cameraBroker = ICameraBroker.Stub.asInterface(service)
            isBound = true

            try {
                sharedMemory = cameraBroker?.sharedMemory
                if (sharedMemory != null) {
                    readBuffer = sharedMemory?.mapReadOnly()
                    _brokerState.value = "Connected"
                } else {
                    _brokerState.value = "SharedMemory N/A"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SharedMemory", e)
                _brokerState.value = "Error"
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Broker disconnected")
            cameraBroker = null
            isBound = false
            _brokerState.value = "Disconnected"
            stopStreaming()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "Audio permission granted")
            startAudioCapture()
        } else {
            Log.e(TAG, "Audio permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializeWebRtc()
        initializeSignaling()

        setContent {
            CameraClientTheme {
                HostScreen(
                    brokerState = _brokerState.value,
                    signalingState = _signalingState.value,
                    webRtcState = _webRtcState.value,
                    dataChannelState = _dataChannelState.value,
                    roomName = _roomName.value,
                    isStreaming = _isStreaming.value,
                    isMuted = _isMuted.value,
                    commandLog = _commandLog,
                    onRoomNameChange = { _roomName.value = it },
                    onConnectBroker = { connectToBroker() },
                    onDisconnectBroker = { disconnectFromBroker() },
                    onJoinRoom = { joinRoom() },
                    onLeaveRoom = { leaveRoom() },
                    onStartStreaming = { startStreaming() },
                    onStopStreaming = { stopStreaming() },
                    onToggleMute = { toggleMute() },
                    onSendNotification = { sendNotification(it) },
                    onRendererCreated = { renderer ->
                        surfaceViewRenderer = renderer
                        webRtcManager?.addLocalRenderer(renderer)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        leaveRoom()
        stopStreaming()
        surfaceViewRenderer?.let { webRtcManager?.removeLocalRenderer(it) }
        webRtcManager?.release()
        disconnectFromBroker()
    }

    private fun initializeWebRtc() {
        webRtcManager = WebRtcManager(this).apply {
            initialize()

            onConnectionStateChange = { state ->
                runOnUiThread {
                    _webRtcState.value = state.name
                }
            }

            onLocalDescription = { sdp ->
                // Send offer via signaling
                signalingClient?.sendOffer(sdp)
            }

            onIceCandidate = { candidate ->
                // Send ICE candidate via signaling
                signalingClient?.sendIceCandidate(candidate)
            }

            onDataChannelStateChange = { state ->
                runOnUiThread {
                    _dataChannelState.value = state.name
                }
            }

            onCommandReceived = { command ->
                runOnUiThread {
                    _commandLog.add(0, "< $command")
                    handleCommand(command)
                }
            }
        }
        _webRtcState.value = "Initialized"
    }

    private fun initializeSignaling() {
        signalingClient = SignalingClient().apply {
            onConnected = {
                runOnUiThread {
                    _signalingState.value = "Connected"
                    addLog("Signaling connected")
                }
            }

            onDisconnected = { reason ->
                runOnUiThread {
                    _signalingState.value = "Disconnected"
                    addLog("Signaling disconnected: $reason")
                }
            }

            onRoomJoined = { room ->
                runOnUiThread {
                    _signalingState.value = "In room: $room"
                    addLog("Joined room: $room")
                }
            }

            onPeerJoined = { peerId ->
                runOnUiThread {
                    addLog("Peer joined: $peerId")
                    // Create offer for new peer
                    if (_isStreaming.value) {
                        webRtcManager?.createOffer()
                    }
                }
            }

            onCallReady = { peerId ->
                runOnUiThread {
                    addLog("Peer already in room: $peerId")
                    // Create offer if already streaming
                    if (_isStreaming.value) {
                        webRtcManager?.createOffer()
                    }
                }
            }

            onPeerLeft = { peerId ->
                runOnUiThread {
                    addLog("Peer left: $peerId")
                }
            }

            onAnswerReceived = { sdp, peerId ->
                Log.d(TAG, "Answer received from: $peerId")
                webRtcManager?.setRemoteDescription(sdp)
            }

            onIceCandidateReceived = { candidate, peerId ->
                Log.d(TAG, "ICE candidate received from: $peerId")
                webRtcManager?.addIceCandidate(candidate)
            }

            onError = { error ->
                runOnUiThread {
                    addLog("Error: $error")
                }
            }
        }
    }

    private fun handleCommand(command: String) {
        Log.d(TAG, "Handling command: $command")
        when {
            command.startsWith("MUTE") -> {
                _isMuted.value = true
                webRtcManager?.setAudioEnabled(false)
            }
            command.startsWith("UNMUTE") -> {
                _isMuted.value = false
                webRtcManager?.setAudioEnabled(true)
            }
            command.startsWith("PING") -> {
                sendNotification("PONG")
            }
            else -> {
                Log.d(TAG, "Unknown command: $command")
            }
        }
    }

    private fun addLog(message: String) {
        _commandLog.add(0, message)
        if (_commandLog.size > 50) {
            _commandLog.removeAt(_commandLog.size - 1)
        }
    }

    private fun connectToBroker() {
        if (isBound) return

        _brokerState.value = "Connecting..."

        val intent = Intent(BROKER_ACTION).apply {
            setPackage(BROKER_PACKAGE)
        }

        try {
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                _brokerState.value = "Failed"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
            _brokerState.value = "Error"
        }
    }

    private fun disconnectFromBroker() {
        stopStreaming()

        try {
            readBuffer?.let { android.os.SharedMemory.unmap(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmap buffer", e)
        }
        readBuffer = null

        try {
            sharedMemory?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close SharedMemory", e)
        }
        sharedMemory = null

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        cameraBroker = null
        _brokerState.value = "Disconnected"
    }

    private fun joinRoom() {
        val room = _roomName.value.trim()
        if (room.isEmpty()) {
            addLog("Room name is empty")
            return
        }

        _signalingState.value = "Joining..."
        signalingClient?.joinRoom(room, asHost = true)
    }

    private fun leaveRoom() {
        signalingClient?.leaveRoom()
        _signalingState.value = "Disconnected"
    }

    private fun startStreaming() {
        val broker = cameraBroker
        val buffer = readBuffer

        if (broker == null || buffer == null) {
            addLog("Broker not connected")
            return
        }

        if (!signalingClient?.isConnected()!!) {
            addLog("Not connected to signaling server")
            return
        }

        // Start video capture
        webRtcManager?.startVideoCapture(broker, buffer)

        // Check audio permission and start audio
        checkAudioPermissionAndStart()

        // Create peer connection
        webRtcManager?.createPeerConnection()

        // Create offer
        webRtcManager?.createOffer()

        _isStreaming.value = true
        _webRtcState.value = "Streaming"
        addLog("Streaming started")
    }

    private fun checkAudioPermissionAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startAudioCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioCapture() {
        webRtcManager?.startAudioCapture()
    }

    private fun stopStreaming() {
        webRtcManager?.stopCapture()
        webRtcManager?.closePeerConnection()
        _isStreaming.value = false
        _webRtcState.value = "Stopped"
        _dataChannelState.value = "Closed"
        addLog("Streaming stopped")
    }

    private fun toggleMute() {
        _isMuted.value = !_isMuted.value
        webRtcManager?.setAudioEnabled(!_isMuted.value)
    }

    private fun sendNotification(message: String) {
        val sent = webRtcManager?.sendNotification(message) ?: false
        if (sent) {
            _commandLog.add(0, "> $message")
        }
    }
}

@Composable
fun HostScreen(
    brokerState: String,
    signalingState: String,
    webRtcState: String,
    dataChannelState: String,
    roomName: String,
    isStreaming: Boolean,
    isMuted: Boolean,
    commandLog: List<String>,
    onRoomNameChange: (String) -> Unit,
    onConnectBroker: () -> Unit,
    onDisconnectBroker: () -> Unit,
    onJoinRoom: () -> Unit,
    onLeaveRoom: () -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onToggleMute: () -> Unit,
    onSendNotification: (String) -> Unit,
    onRendererCreated: (SurfaceViewRenderer) -> Unit
) {
    val isInRoom = signalingState.startsWith("In room")

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left panel - Video Preview
            Card(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                onRendererCreated(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (!isStreaming) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Not Streaming", color = Color.White)
                        }
                    }

                    if (isMuted && isStreaming) {
                        Text(
                            text = "MUTED",
                            color = Color.Red,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(4.dp)
                        )
                    }
                }
            }

            // Right panel - Controls
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "WebRTC Host",
                    style = MaterialTheme.typography.titleMedium
                )

                // Status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        StatusRow("Broker", brokerState, brokerState == "Connected")
                        StatusRow("Signaling", signalingState, isInRoom)
                        StatusRow("WebRTC", webRtcState, webRtcState == "CONNECTED")
                        StatusRow("DataChannel", dataChannelState, dataChannelState == "OPEN")
                    }
                }

                // Room name input
                OutlinedTextField(
                    value = roomName,
                    onValueChange = onRoomNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Room Name") },
                    singleLine = true,
                    enabled = !isInRoom
                )

                // Broker controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = onConnectBroker,
                        modifier = Modifier.weight(1f),
                        enabled = brokerState == "Disconnected",
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text("Broker", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = if (isInRoom) onLeaveRoom else onJoinRoom,
                        modifier = Modifier.weight(1f),
                        colors = if (isInRoom) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (isInRoom) "Leave" else "Join",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Streaming controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = if (isStreaming) onStopStreaming else onStartStreaming,
                        modifier = Modifier.weight(1f),
                        colors = if (isStreaming) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        enabled = brokerState == "Connected" && isInRoom,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (isStreaming) "Stop" else "Start",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = onToggleMute,
                        modifier = Modifier.weight(1f),
                        enabled = isStreaming,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (isMuted) "Unmute" else "Mute",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                HorizontalDivider()

                // Send alert notification
                Button(
                    onClick = { onSendNotification("NOTIFY:ALERT") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dataChannelState == "OPEN",
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Send ALERT", style = MaterialTheme.typography.bodySmall)
                }

                // Log
                Text(
                    text = "Log",
                    style = MaterialTheme.typography.labelSmall
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    ) {
                        items(commandLog) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    log.startsWith(">") -> MaterialTheme.colorScheme.primary
                                    log.startsWith("<") -> MaterialTheme.colorScheme.secondary
                                    log.startsWith("Error") -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusRow(title: String, status: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

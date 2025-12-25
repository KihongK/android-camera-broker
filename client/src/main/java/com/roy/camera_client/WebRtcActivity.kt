package com.roy.camera_client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roy.camera_client.ui.theme.CameraClientTheme
import com.roy.camera_client.webrtc.WebRtcManager
import com.roy.camera_test.ICameraBroker
import org.webrtc.SurfaceViewRenderer
import java.nio.ByteBuffer

class WebRtcActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WebRtcActivity"
        private const val BROKER_PACKAGE = "com.roy.camera_test"
        private const val BROKER_ACTION = "com.roy.camera_test.CAMERA_BROKER"
    }

    private var cameraBroker: ICameraBroker? = null
    private var isBound = false
    private var sharedMemory: android.os.SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    private var webRtcManager: WebRtcManager? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null

    // UI State
    private val _connectionState = mutableStateOf("Disconnected")
    private val _webRtcState = mutableStateOf("Not initialized")
    private val _isCapturing = mutableStateOf(false)
    private val _sdpOffer = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected: $name")
            cameraBroker = ICameraBroker.Stub.asInterface(service)
            isBound = true
            _connectionState.value = "Connected to Broker"

            try {
                sharedMemory = cameraBroker?.sharedMemory
                if (sharedMemory != null) {
                    readBuffer = sharedMemory?.mapReadOnly()
                    Log.d(TAG, "SharedMemory mapped: ${sharedMemory?.size} bytes")
                    _connectionState.value = "SharedMemory ready"
                } else {
                    _connectionState.value = "SharedMemory not available"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SharedMemory", e)
                _connectionState.value = "Error: ${e.message}"
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            cameraBroker = null
            isBound = false
            _connectionState.value = "Disconnected"
            stopWebRtcCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize WebRTC
        webRtcManager = WebRtcManager(this).apply {
            initialize()
            onLocalDescription = { sdp ->
                _sdpOffer.value = sdp.description
            }
            onConnectionStateChange = { state ->
                _webRtcState.value = "P2P: $state"
            }
        }
        _webRtcState.value = "Initialized"

        setContent {
            CameraClientTheme {
                WebRtcScreen(
                    connectionState = _connectionState.value,
                    webRtcState = _webRtcState.value,
                    isCapturing = _isCapturing.value,
                    sdpOffer = _sdpOffer.value,
                    onConnect = { connectToBroker() },
                    onDisconnect = { disconnectFromBroker() },
                    onStartCapture = { startWebRtcCapture() },
                    onStopCapture = { stopWebRtcCapture() },
                    onCreateOffer = { createP2POffer() },
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
        stopWebRtcCapture()
        surfaceViewRenderer?.let { webRtcManager?.removeLocalRenderer(it) }
        webRtcManager?.release()
        disconnectFromBroker()
    }

    private fun connectToBroker() {
        if (isBound) {
            Log.d(TAG, "Already connected")
            return
        }

        _connectionState.value = "Connecting..."

        val intent = Intent(BROKER_ACTION).apply {
            setPackage(BROKER_PACKAGE)
        }

        try {
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                _connectionState.value = "Failed to bind. Is Broker running?"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
            _connectionState.value = "Error: ${e.message}"
        }
    }

    private fun disconnectFromBroker() {
        stopWebRtcCapture()

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
        _connectionState.value = "Disconnected"
    }

    private fun startWebRtcCapture() {
        val broker = cameraBroker
        val buffer = readBuffer

        if (broker == null || buffer == null) {
            _webRtcState.value = "Not connected to broker"
            return
        }

        webRtcManager?.startCapture(broker, buffer)
        _isCapturing.value = true
        _webRtcState.value = "Capturing"
    }

    private fun stopWebRtcCapture() {
        webRtcManager?.stopCapture()
        _isCapturing.value = false
        _webRtcState.value = "Stopped"
    }

    private fun createP2POffer() {
        webRtcManager?.createPeerConnection()
        webRtcManager?.createOffer()
        _webRtcState.value = "Creating offer..."
    }
}

@Composable
fun WebRtcScreen(
    connectionState: String,
    webRtcState: String,
    isCapturing: Boolean,
    sdpOffer: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onCreateOffer: () -> Unit,
    onRendererCreated: (SurfaceViewRenderer) -> Unit
) {
    val context = LocalContext.current

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "WebRTC Client",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(
                    title = "Broker",
                    status = connectionState,
                    isConnected = connectionState == "SharedMemory ready",
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    title = "WebRTC",
                    status = webRtcState,
                    isConnected = isCapturing,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connect/Disconnect buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    enabled = connectionState == "Disconnected"
                ) {
                    Text("Connect")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // WebRTC Video Preview
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Capture controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = if (isCapturing) onStopCapture else onStartCapture,
                    modifier = Modifier.weight(1f),
                    colors = if (isCapturing) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    enabled = connectionState == "SharedMemory ready"
                ) {
                    Text(if (isCapturing) "Stop Capture" else "Start Capture")
                }

                OutlinedButton(
                    onClick = onCreateOffer,
                    modifier = Modifier.weight(1f),
                    enabled = isCapturing
                ) {
                    Text("Create Offer")
                }
            }

            // SDP Offer display (for debugging/testing)
            if (sdpOffer != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "SDP Offer (first 200 chars):",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = sdpOffer.take(200) + "...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    status: String,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

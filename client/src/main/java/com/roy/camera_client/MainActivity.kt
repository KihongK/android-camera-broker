package com.roy.camera_client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.roy.camera_client.ui.theme.CameraClientTheme
import com.roy.camera_test.FrameInfo
import com.roy.camera_test.ICameraBroker
import com.roy.camera_test.util.YuvConverter
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraClient"
        private const val BROKER_PACKAGE = "com.roy.camera_test"
        private const val BROKER_ACTION = "com.roy.camera_test.CAMERA_BROKER"
    }

    private var cameraBroker: ICameraBroker? = null
    private var isBound = false
    private var sharedMemory: android.os.SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    // UI State
    private val _connectionState = mutableStateOf("Disconnected")
    private val _previewBitmap = mutableStateOf<Bitmap?>(null)
    private val _frameInfo = mutableStateOf("No frame")
    private val _isStreaming = mutableStateOf(false)

    private var streamJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected: $name")
            cameraBroker = ICameraBroker.Stub.asInterface(service)
            isBound = true
            _connectionState.value = "Connected to Broker"

            // Get SharedMemory via AIDL
            try {
                sharedMemory = cameraBroker?.sharedMemory
                if (sharedMemory != null) {
                    readBuffer = sharedMemory?.mapReadOnly()
                    Log.d(TAG, "SharedMemory mapped: ${sharedMemory?.size} bytes")
                    _connectionState.value = "SharedMemory ready (${sharedMemory?.size} bytes)"
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
            stopStreaming()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CameraClientTheme {
                ClientScreen(
                    connectionState = _connectionState.value,
                    bitmap = _previewBitmap.value,
                    frameInfo = _frameInfo.value,
                    isStreaming = _isStreaming.value,
                    onConnect = { connectToBroker() },
                    onDisconnect = { disconnectFromBroker() },
                    onStartStream = { startStreaming() },
                    onStopStream = { stopStreaming() },
                    onOpenWebRtc = { openWebRtcActivity() }
                )
            }
        }
    }

    private fun openWebRtcActivity() {
        startActivity(Intent(this, WebRtcActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
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
                _connectionState.value = "Failed to bind. Is Broker app running?"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
            _connectionState.value = "Error: ${e.message}"
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
        _connectionState.value = "Disconnected"
    }

    private fun startStreaming() {
        if (_isStreaming.value) return
        _isStreaming.value = true

        streamJob = CoroutineScope(Dispatchers.Default).launch {
            var lastFrameCounter = -1L

            while (isActive && _isStreaming.value) {
                try {
                    val broker = cameraBroker
                    val buffer = readBuffer

                    if (broker == null || buffer == null) {
                        withContext(Dispatchers.Main) {
                            _frameInfo.value = "Not connected"
                        }
                        delay(100)
                        continue
                    }

                    val isStreamingNow = broker.isStreaming
                    if (!isStreamingNow) {
                        withContext(Dispatchers.Main) {
                            _frameInfo.value = "Broker not streaming"
                        }
                        delay(100)
                        continue
                    }

                    val frameCounter = broker.frameCounter
                    Log.d(TAG, "frameCounter=$frameCounter, lastFrameCounter=$lastFrameCounter")
                    if (frameCounter == lastFrameCounter) {
                        delay(5)
                        continue
                    }
                    lastFrameCounter = frameCounter

                    val info: FrameInfo? = broker.currentFrameInfo
                    if (info == null) {
                        withContext(Dispatchers.Main) {
                            _frameInfo.value = "Frame info null (counter=$frameCounter)"
                        }
                        delay(10)
                        continue
                    }

                    Log.d(TAG, "Frame: ${info.width}x${info.height}, Y=${info.yPlaneSize}, U=${info.uPlaneSize}, V=${info.vPlaneSize}")

                    // Convert YUV to Bitmap
                    val bitmap = synchronized(buffer) {
                        buffer.position(0)  // Reset position before reading
                        YuvConverter.yuv420ToBitmap(
                            buffer = buffer,
                            width = info.width,
                            height = info.height,
                            ySize = info.yPlaneSize,
                            uSize = info.uPlaneSize,
                            vSize = info.vPlaneSize,
                            uvPixelStride = info.uvPixelStride
                        )
                    }

                    withContext(Dispatchers.Main) {
                        _previewBitmap.value = bitmap
                        _frameInfo.value = buildString {
                            append("Frame: $frameCounter\n")
                            append("Size: ${info.width}x${info.height}\n")
                            append("Timestamp: ${info.timestamp / 1_000_000}ms")
                        }
                    }

                    delay(33) // ~30 FPS
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error", e)
                    withContext(Dispatchers.Main) {
                        _frameInfo.value = "Error: ${e.message}"
                    }
                    delay(100)
                }
            }
        }
    }

    private fun stopStreaming() {
        _isStreaming.value = false
        streamJob?.cancel()
        streamJob = null
    }
}

@Composable
fun ClientScreen(
    connectionState: String,
    bitmap: Bitmap?,
    frameInfo: String,
    isStreaming: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onOpenWebRtc: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Camera Client",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (connectionState.startsWith("SharedMemory"))
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = connectionState,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
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
                    enabled = !connectionState.startsWith("SharedMemory")
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

            // Preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Camera Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "No Preview",
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Frame info
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = frameInfo,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stream controls
            Button(
                onClick = if (isStreaming) onStopStream else onStartStream,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isStreaming) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                enabled = connectionState.startsWith("SharedMemory")
            ) {
                Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // WebRTC button
            OutlinedButton(
                onClick = onOpenWebRtc,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Open WebRTC Preview")
            }
        }
    }
}

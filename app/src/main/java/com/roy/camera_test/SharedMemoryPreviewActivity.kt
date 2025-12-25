package com.roy.camera_test

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.os.SharedMemory
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
import com.roy.camera_test.service.CameraBrokerService
import com.roy.camera_test.ui.theme.Camera_testTheme
import com.roy.camera_test.util.YuvConverter
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class SharedMemoryPreviewActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SharedMemoryPreview"
    }

    private var brokerService: CameraBrokerService? = null
    private var isBound = false
    private var sharedMemory: SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    // State for UI
    private val _previewBitmap = mutableStateOf<Bitmap?>(null)
    private val _frameInfo = mutableStateOf<String>("Connecting...")
    private val _isRunning = mutableStateOf(false)

    private var previewJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected, binder type: ${service?.javaClass?.simpleName}")

            // Use LocalBinder for same-process access
            if (service is CameraBrokerService.LocalBinder) {
                brokerService = service.getService()
                isBound = true

                // Get SharedMemory directly from service
                try {
                    sharedMemory = brokerService?.getSharedMemoryDirect()
                    if (sharedMemory != null) {
                        readBuffer = sharedMemory?.mapReadOnly()
                        Log.d(TAG, "SharedMemory mapped: ${sharedMemory?.size} bytes")
                        _frameInfo.value = "SharedMemory connected. Press Start to preview."
                    } else {
                        _frameInfo.value = "SharedMemory not available. Start camera service first."
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to map SharedMemory", e)
                    _frameInfo.value = "Error: ${e.message}"
                }
            } else {
                Log.e(TAG, "Unexpected binder type")
                _frameInfo.value = "Error: Wrong binder type"
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            brokerService = null
            isBound = false
            stopPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Camera_testTheme {
                PreviewScreen(
                    bitmap = _previewBitmap.value,
                    frameInfo = _frameInfo.value,
                    isRunning = _isRunning.value,
                    onStart = { startPreview() },
                    onStop = { stopPreview() },
                    onBack = { finish() }
                )
            }
        }

        // Bind to CameraBrokerService
        bindToBrokerService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        unmapSharedMemory()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun bindToBrokerService() {
        val intent = Intent(this, CameraBrokerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unmapSharedMemory() {
        try {
            readBuffer?.let { SharedMemory.unmap(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmap buffer", e)
        }
        readBuffer = null
        sharedMemory = null
    }

    private fun startPreview() {
        if (_isRunning.value) return
        _isRunning.value = true

        previewJob = CoroutineScope(Dispatchers.Default).launch {
            var lastFrameCounter = -1L

            while (isActive && _isRunning.value) {
                try {
                    val service = brokerService
                    val buffer = readBuffer

                    if (service == null || buffer == null) {
                        withContext(Dispatchers.Main) {
                            _frameInfo.value = "Not connected"
                        }
                        delay(100)
                        continue
                    }

                    if (!service.isStreamingDirect()) {
                        withContext(Dispatchers.Main) {
                            _frameInfo.value = "Camera not streaming"
                        }
                        delay(100)
                        continue
                    }

                    val frameCounter = service.getFrameCounterDirect()
                    if (frameCounter == lastFrameCounter) {
                        delay(5) // Wait for new frame
                        continue
                    }
                    lastFrameCounter = frameCounter

                    val info = service.getCurrentFrameInfoDirect()
                    if (info == null) {
                        delay(10)
                        continue
                    }

                    // Convert YUV to Bitmap
                    val bitmap = synchronized(buffer) {
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
                            append("Resolution: ${info.width}x${info.height}\n")
                            append("Timestamp: ${info.timestamp / 1_000_000}ms")
                        }
                    }

                    delay(33) // ~30 FPS cap
                } catch (e: Exception) {
                    Log.e(TAG, "Preview error", e)
                    withContext(Dispatchers.Main) {
                        _frameInfo.value = "Error: ${e.message}"
                    }
                    delay(100)
                }
            }
        }
    }

    private fun stopPreview() {
        _isRunning.value = false
        previewJob?.cancel()
        previewJob = null
    }
}

@Composable
fun PreviewScreen(
    bitmap: Bitmap?,
    frameInfo: String,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit
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
                text = "SharedMemory Preview",
                style = MaterialTheme.typography.headlineSmall
            )

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

            Spacer(modifier = Modifier.height(16.dp))

            // Frame info card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = frameInfo,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = if (isRunning) onStop else onStart,
                    modifier = Modifier.weight(1f),
                    colors = if (isRunning) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (isRunning) "Stop" else "Start Preview")
                }

                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Back")
                }
            }
        }
    }
}

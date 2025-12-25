package com.roy.camera_test

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.roy.camera_test.service.CameraBrokerService
import com.roy.camera_test.ui.theme.Camera_testTheme
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var brokerService: CameraBrokerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected, binder: ${service?.javaClass?.simpleName}")
            if (service is CameraBrokerService.LocalBinder) {
                brokerService = service.getService()
                isBound = true
            } else {
                Log.e(TAG, "Unexpected binder type")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            brokerService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            Log.d(TAG, "Camera permission granted")
            startCameraService()
        } else {
            Log.e(TAG, "Camera permission denied")
            // Open app settings directly
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        android.widget.Toast.makeText(
            this,
            "Please enable Camera permission in Settings",
            android.widget.Toast.LENGTH_LONG
        ).show()

        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Camera_testTheme {
                MainScreen(
                    onStartService = { checkPermissionsAndStart() },
                    onStopService = { stopCameraService() },
                    onGetStatus = { getServiceStatus() },
                    onOpenPreview = { openPreviewActivity() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startCameraService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startCameraService() {
        Log.d(TAG, "Starting camera service")
        val intent = Intent(this, CameraBrokerService::class.java)
        startForegroundService(intent)

        // Bind to service
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopCameraService() {
        Log.d(TAG, "Stopping camera service")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, CameraBrokerService::class.java))
    }

    private fun getServiceStatus(): String {
        return try {
            val service = brokerService
            if (service != null && isBound) {
                val streaming = service.isStreamingDirect()
                val frameCount = service.getFrameCounterDirect()
                val frameInfo = service.getCurrentFrameInfoDirect()

                buildString {
                    append("Streaming: $streaming\n")
                    append("Frame Count: $frameCount\n")
                    if (frameInfo != null) {
                        append("Resolution: ${frameInfo.width}x${frameInfo.height}\n")
                        append("Timestamp: ${frameInfo.timestamp}")
                    }
                }
            } else {
                "Service not connected"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun openPreviewActivity() {
        startActivity(Intent(this, SharedMemoryPreviewActivity::class.java))
    }
}

@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGetStatus: () -> String,
    onOpenPreview: () -> Unit
) {
    var statusText by remember { mutableStateOf("Ready") }
    var internalTestEnabled by remember { mutableStateOf(true) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Camera Broker Service",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onStartService,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Camera Service")
            }

            Button(
                onClick = onStopService,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop Camera Service")
            }

            OutlinedButton(
                onClick = { statusText = onGetStatus() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Status")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Internal test mode toggle
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Internal Test Mode",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (internalTestEnabled)
                                "Preview available in this app"
                            else
                                "Disabled for external client app testing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = internalTestEnabled,
                        onCheckedChange = { internalTestEnabled = it }
                    )
                }
            }

            // SharedMemory Preview button (only when internal test enabled)
            if (internalTestEnabled) {
                Button(
                    onClick = onOpenPreview,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Open SharedMemory Preview")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Internal preview disabled.\nTest with external client app using:\nadb shell am start -a com.roy.camera_test.CAMERA_BROKER",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

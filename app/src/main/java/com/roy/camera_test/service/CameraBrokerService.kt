package com.roy.camera_test.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SharedMemory
import android.util.Log
import androidx.core.app.NotificationCompat
import com.roy.camera_test.FrameInfo
import com.roy.camera_test.ICameraBroker
import com.roy.camera_test.R
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class CameraBrokerService : Service() {

    companion object {
        private const val TAG = "CameraBrokerService"
        private const val CHANNEL_ID = "camera_broker_channel"
        private const val NOTIFICATION_ID = 1

        // Camera configuration
        const val FRAME_WIDTH = 640
        const val FRAME_HEIGHT = 480

        // SharedMemory size - needs extra space for row stride padding
        // YUV_420_888 with stride can be up to width * height * 2
        val SHARED_MEMORY_SIZE = FRAME_WIDTH * FRAME_HEIGHT * 2 + 4096
    }

    // Camera components
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // SharedMemory
    private var _sharedMemory: android.os.SharedMemory? = null
    private var _sharedMemoryBuffer: ByteBuffer? = null

    // Frame state
    private val frameCounter = AtomicLong(0)
    @Volatile private var _currentFrameInfo: FrameInfo? = null
    @Volatile private var _isStreaming = false

    // Local binder for same-process access (avoids AIDL serialization issues)
    inner class LocalBinder : android.os.Binder() {
        fun getService(): CameraBrokerService = this@CameraBrokerService
    }

    private val localBinder = LocalBinder()

    // AIDL binder for cross-process access
    private val aidlBinder = object : ICameraBroker.Stub() {
        override fun getSharedMemory(): android.os.SharedMemory? {
            Log.d(TAG, "AIDL getSharedMemory called, sharedMemory=${_sharedMemory?.size} bytes")
            return _sharedMemory
        }

        override fun getCurrentFrameInfo(): FrameInfo? {
            return _currentFrameInfo
        }

        override fun getFrameCounter(): Long = this@CameraBrokerService.frameCounter.get()

        override fun isStreaming(): Boolean = this@CameraBrokerService._isStreaming
    }

    // Direct accessors for same-process clients
    fun getSharedMemoryDirect(): SharedMemory? = _sharedMemory
    fun getCurrentFrameInfoDirect(): FrameInfo? = _currentFrameInfo
    fun getFrameCounterDirect(): Long = frameCounter.get()
    fun isStreamingDirect(): Boolean = _isStreaming

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        createNotificationChannel()
        startBackgroundThread()
        initSharedMemory()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        openCamera()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service onBind: action=${intent?.action}")
        // Use AIDL binder for external apps (via action), LocalBinder for same app
        return if (intent?.action == "com.roy.camera_test.CAMERA_BROKER") {
            Log.d(TAG, "Returning AIDL binder for external app")
            aidlBinder
        } else {
            Log.d(TAG, "Returning LocalBinder for same app")
            localBinder
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        closeCamera()
        stopBackgroundThread()
        releaseSharedMemory()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Broker Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera frame sharing service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Broker")
            .setContentText("Sharing camera frames...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun initSharedMemory() {
        try {
            _sharedMemory = SharedMemory.create("camera_frame", SHARED_MEMORY_SIZE)
            _sharedMemoryBuffer = _sharedMemory?.mapReadWrite()
            Log.d(TAG, "SharedMemory created: $SHARED_MEMORY_SIZE bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SharedMemory", e)
        }
    }

    private fun releaseSharedMemory() {
        try {
            SharedMemory.unmap(_sharedMemoryBuffer!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmap SharedMemory buffer", e)
        }
        try {
            _sharedMemory?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close SharedMemory", e)
        }
        _sharedMemory = null
        _sharedMemoryBuffer = null
    }

    private fun openCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager?.cameraIdList?.firstOrNull()

            if (cameraId == null) {
                Log.e(TAG, "No camera found")
                return
            }

            Log.d(TAG, "Opening camera: $cameraId")

            // Create ImageReader for YUV frames
            imageReader = ImageReader.newInstance(
                FRAME_WIDTH, FRAME_HEIGHT,
                ImageFormat.YUV_420_888,
                2 // Max images in buffer
            ).apply {
                setOnImageAvailableListener({ reader ->
                    processImage(reader)
                }, backgroundHandler)
            }

            cameraManager?.openCamera(cameraId, cameraStateCallback, backgroundHandler)

        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected")
            camera.close()
            cameraDevice = null
            _isStreaming = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
            _isStreaming = false
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            val surfaces = listOf(reader.surface)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = surfaces.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfig,
                    backgroundHandler?.looper?.let { HandlerExecutor(it) } ?: return,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Capture session configured")
                            captureSession = session
                            startPreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed")
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Capture session configured")
                            captureSession = session
                            startPreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private class HandlerExecutor(private val looper: Looper) : java.util.concurrent.Executor {
        private val handler = Handler(looper)
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }

            session.setRepeatingRequest(
                requestBuilder.build(),
                null,
                backgroundHandler
            )

            _isStreaming = true
            Log.d(TAG, "Preview started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }

    private fun processImage(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "acquireLatestImage returned null")
            return
        }

        try {
            val buffer = _sharedMemoryBuffer
            if (buffer == null) {
                Log.e(TAG, "sharedMemoryBuffer is null")
                image.close()
                return
            }

            Log.d(TAG, "Processing frame: ${image.width}x${image.height}")

            // Get YUV planes
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // Write to SharedMemory
            synchronized(buffer) {
                buffer.position(0)
                buffer.put(yBuffer)
                buffer.put(uBuffer)
                buffer.put(vBuffer)
            }

            // Update frame info
            val count = frameCounter.incrementAndGet()
            if (count % 30 == 0L) {
                Log.d(TAG, "Frame count: $count")
            }
            _currentFrameInfo = FrameInfo(
                width = image.width,
                height = image.height,
                format = image.format,
                timestamp = image.timestamp,
                frameCounter = count,
                yPlaneSize = ySize,
                uPlaneSize = uSize,
                vPlaneSize = vSize,
                yRowStride = yPlane.rowStride,
                uvRowStride = uPlane.rowStride,
                uvPixelStride = uPlane.pixelStride
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
        } finally {
            image.close()
        }
    }

    private fun closeCamera() {
        _isStreaming = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }
}

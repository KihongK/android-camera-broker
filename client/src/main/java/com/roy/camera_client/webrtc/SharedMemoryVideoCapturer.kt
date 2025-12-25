package com.roy.camera_client.webrtc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.roy.camera_test.FrameInfo
import com.roy.camera_test.ICameraBroker
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom VideoCapturer that reads frames from SharedMemory (via CameraBroker)
 * and feeds them to WebRTC.
 */
class SharedMemoryVideoCapturer(
    private val broker: ICameraBroker,
    private val sharedMemoryBuffer: ByteBuffer
) : VideoCapturer {

    companion object {
        private const val TAG = "SharedMemoryCapturer"
        private const val FRAME_INTERVAL_MS = 33L // ~30 FPS
    }

    private var capturerObserver: CapturerObserver? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val isCapturing = AtomicBoolean(false)
    private var lastFrameCounter = -1L

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing.get()) return

            try {
                captureFrame()
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing frame", e)
            }

            captureHandler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        observer: CapturerObserver?
    ) {
        Log.d(TAG, "initialize")
        this.capturerObserver = observer
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "startCapture: ${width}x${height} @ ${framerate}fps")

        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing")
            return
        }

        isCapturing.set(true)

        captureThread = HandlerThread("SharedMemoryCapturer").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        captureHandler?.post(captureRunnable)
    }

    override fun stopCapture() {
        Log.d(TAG, "stopCapture")

        isCapturing.set(false)
        captureHandler?.removeCallbacks(captureRunnable)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "changeCaptureFormat: ${width}x${height} @ ${framerate}fps")
        // Format is determined by the broker, so we ignore this
    }

    override fun dispose() {
        Log.d(TAG, "dispose")
        stopCapture()
    }

    override fun isScreencast(): Boolean = false

    private fun captureFrame() {
        val observer = capturerObserver ?: return

        try {
            // Check if broker is streaming
            if (!broker.isStreaming) {
                return
            }

            // Check for new frame
            val frameCounter = broker.frameCounter
            if (frameCounter == lastFrameCounter) {
                return
            }
            lastFrameCounter = frameCounter

            // Get frame info
            val info: FrameInfo = broker.currentFrameInfo ?: return

            // Read YUV data from SharedMemory
            val nv21Data = synchronized(sharedMemoryBuffer) {
                sharedMemoryBuffer.position(0)
                readAndConvertToNv21(info)
            }

            // Create VideoFrame
            val timestampNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
            val buffer = NV21Buffer(nv21Data, info.width, info.height, null)
            val videoFrame = VideoFrame(buffer, 0, timestampNs)

            // Send to WebRTC
            observer.onFrameCaptured(videoFrame)
            videoFrame.release()

            if (frameCounter % 30 == 0L) {
                Log.d(TAG, "Captured frame: $frameCounter, ${info.width}x${info.height}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture frame", e)
        }
    }

    /**
     * Read YUV_420_888 data from SharedMemory and convert to NV21.
     * NV21 format: YYYYYYYY VUVU (Y plane followed by interleaved V and U)
     */
    private fun readAndConvertToNv21(info: FrameInfo): ByteArray {
        val width = info.width
        val height = info.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Read Y plane
        val yBytes = ByteArray(info.yPlaneSize)
        sharedMemoryBuffer.get(yBytes)

        // Read U plane
        val uBytes = ByteArray(info.uPlaneSize)
        sharedMemoryBuffer.get(uBytes)

        // Read V plane
        val vBytes = ByteArray(info.vPlaneSize)
        sharedMemoryBuffer.get(vBytes)

        // Copy Y plane
        System.arraycopy(yBytes, 0, nv21, 0, minOf(yBytes.size, ySize))

        // Interleave V and U for NV21 format
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvPixelStride = info.uvPixelStride

        var nv21Index = ySize
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = if (uvPixelStride == 1) {
                    // Planar format
                    row * uvWidth + col
                } else {
                    // Semi-planar format (stride = 2)
                    row * width + col * uvPixelStride
                }

                if (uvIndex < vBytes.size && nv21Index < nv21.size) {
                    nv21[nv21Index++] = vBytes[uvIndex]
                }
                if (uvIndex < uBytes.size && nv21Index < nv21.size) {
                    nv21[nv21Index++] = uBytes[uvIndex]
                }
            }
        }

        return nv21
    }
}

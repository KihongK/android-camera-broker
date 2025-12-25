package com.roy.camera_test.util

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Utility for converting YUV_420_888 data to Bitmap.
 */
object YuvConverter {

    /**
     * Convert YUV_420_888 data from SharedMemory buffer to Bitmap.
     *
     * @param buffer ByteBuffer containing YUV data
     * @param width Frame width
     * @param height Frame height
     * @param ySize Size of Y plane
     * @param uSize Size of U plane
     * @param vSize Size of V plane
     * @param uvPixelStride Pixel stride for UV planes (1 = planar, 2 = semi-planar)
     */
    fun yuv420ToBitmap(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        ySize: Int,
        uSize: Int,
        vSize: Int,
        uvPixelStride: Int
    ): Bitmap? {
        return try {
            buffer.position(0)

            // Read Y plane
            val yBytes = ByteArray(ySize)
            buffer.get(yBytes)

            // Read U plane
            val uBytes = ByteArray(uSize)
            buffer.get(uBytes)

            // Read V plane
            val vBytes = ByteArray(vSize)
            buffer.get(vBytes)

            // Convert to NV21 format (YYYYYYYYVUVU...)
            val nv21 = convertToNv21(yBytes, uBytes, vBytes, width, height, uvPixelStride)

            // Use YuvImage to convert to Bitmap
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, outputStream)
            val jpegBytes = outputStream.toByteArray()

            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Convert YUV_420_888 planes to NV21 format.
     * NV21: YYYYYYYY VUVU (V and U interleaved)
     */
    private fun convertToNv21(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        uvPixelStride: Int
    ): ByteArray {
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Copy Y plane
        System.arraycopy(yBytes, 0, nv21, 0, minOf(yBytes.size, ySize))

        // Interleave V and U for NV21
        val uvWidth = width / 2
        val uvHeight = height / 2

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

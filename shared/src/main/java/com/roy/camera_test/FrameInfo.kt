package com.roy.camera_test

import android.os.Parcel
import android.os.Parcelable

/**
 * Frame metadata shared between broker and client apps.
 */
data class FrameInfo(
    val width: Int,
    val height: Int,
    val format: Int,           // ImageFormat (e.g., YUV_420_888)
    val timestamp: Long,       // Frame timestamp in nanoseconds
    val frameCounter: Long,    // Monotonically increasing frame counter
    val yPlaneSize: Int,       // Size of Y plane in bytes
    val uPlaneSize: Int,       // Size of U plane in bytes
    val vPlaneSize: Int,       // Size of V plane in bytes
    val yRowStride: Int,       // Row stride for Y plane
    val uvRowStride: Int,      // Row stride for U/V planes
    val uvPixelStride: Int     // Pixel stride for U/V planes
) : Parcelable {

    constructor(parcel: Parcel) : this(
        width = parcel.readInt(),
        height = parcel.readInt(),
        format = parcel.readInt(),
        timestamp = parcel.readLong(),
        frameCounter = parcel.readLong(),
        yPlaneSize = parcel.readInt(),
        uPlaneSize = parcel.readInt(),
        vPlaneSize = parcel.readInt(),
        yRowStride = parcel.readInt(),
        uvRowStride = parcel.readInt(),
        uvPixelStride = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeInt(format)
        parcel.writeLong(timestamp)
        parcel.writeLong(frameCounter)
        parcel.writeInt(yPlaneSize)
        parcel.writeInt(uPlaneSize)
        parcel.writeInt(vPlaneSize)
        parcel.writeInt(yRowStride)
        parcel.writeInt(uvRowStride)
        parcel.writeInt(uvPixelStride)
    }

    override fun describeContents(): Int = 0

    /**
     * Total size of all planes in SharedMemory buffer.
     */
    val totalSize: Int
        get() = yPlaneSize + uPlaneSize + vPlaneSize

    companion object CREATOR : Parcelable.Creator<FrameInfo> {
        override fun createFromParcel(parcel: Parcel): FrameInfo = FrameInfo(parcel)
        override fun newArray(size: Int): Array<FrameInfo?> = arrayOfNulls(size)
    }
}

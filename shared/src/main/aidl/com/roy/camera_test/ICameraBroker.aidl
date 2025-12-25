package com.roy.camera_test;

import android.os.SharedMemory;
import com.roy.camera_test.FrameInfo;

interface ICameraBroker {
    /**
     * Get the SharedMemory for reading frame data.
     * Returns null if camera is not ready.
     */
    SharedMemory getSharedMemory();

    /**
     * Get current frame information (width, height, timestamp, etc.)
     * Returns null if no frame is available.
     */
    FrameInfo getCurrentFrameInfo();

    /**
     * Get the current frame counter.
     * Clients can poll this to detect new frames.
     */
    long getFrameCounter();

    /**
     * Check if the camera service is ready and streaming.
     */
    boolean isStreaming();
}

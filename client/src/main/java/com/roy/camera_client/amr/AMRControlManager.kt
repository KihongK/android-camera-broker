package com.roy.camera_client.amr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.sk.airbot.amrcontrol.IAMRControl
import com.sk.airbot.amrcontrol.IAMRControlCallback

/**
 * AMR Control Manager
 * - Binds to AMR Control AIDL service
 * - Provides control methods for robot operations
 * - Handles callbacks from AMR service
 */
class AMRControlManager(private val context: Context) {

    companion object {
        private const val TAG = "AMRControlManager"
        private const val AMR_SERVICE_ACTION = "com.sk.airbot.amrcontrol.IAMRControl"
        private const val AMR_SERVICE_PACKAGE = "com.sk.airbot.amragent"
        private const val DEFAULT_DELAY = 0
    }

    private var amrService: IAMRControl? = null
    private var isBound = false

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onCallbackReceived: ((Bundle) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "AMR service connected: ${name?.toShortString()}")
            amrService = IAMRControl.Stub.asInterface(service)
            isBound = true

            try {
                val registered = amrService?.registerCallback(amrCallback) ?: false
                Log.d(TAG, "Callback registration: $registered")
                onConnected?.invoke()
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to register callback", e)
                onError?.invoke("Failed to register callback: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "AMR service disconnected")
            amrService = null
            isBound = false
            onDisconnected?.invoke()
        }
    }

    private val amrCallback = object : IAMRControlCallback.Stub() {
        override fun onModuleCallback(data: Bundle?) {
            data?.let {
                Log.d(TAG, "AMR callback received: $it")
                onCallbackReceived?.invoke(it)
            }
        }
    }

    /**
     * Connect to AMR Control service
     */
    fun connect(): Boolean {
        if (isBound) {
            Log.w(TAG, "Already connected to AMR service")
            return true
        }

        val intent = Intent().apply {
            action = AMR_SERVICE_ACTION
            setPackage(AMR_SERVICE_PACKAGE)
        }

        return try {
            val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!result) {
                Log.e(TAG, "Failed to bind AMR service")
                onError?.invoke("Failed to bind AMR service")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error binding AMR service", e)
            onError?.invoke("Error binding AMR service: ${e.message}")
            false
        }
    }

    /**
     * Disconnect from AMR Control service
     */
    fun disconnect() {
        if (!isBound) return

        try {
            amrService?.unregisterCallback(amrCallback)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error unregistering callback", e)
        }

        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service", e)
        }

        amrService = null
        isBound = false
    }

    fun isConnected(): Boolean = isBound && amrService != null

    // ==================== Movement Control ====================

    /**
     * Manual velocity/rotation control
     * @param ms Movement speed (m/s)
     * @param rs Rotation speed (rad/s)
     */
    fun controlManualVW(ms: Double, rs: Double): Boolean {
        return executeCommand("controlManualVW(ms=$ms, rs=$rs)") {
            amrService?.opControlManualVW(ms, rs, DEFAULT_DELAY)
        }
    }

    /**
     * Move forward
     */
    fun moveForward(speed: Double = 0.1): Boolean = controlManualVW(speed, 0.0)

    /**
     * Move backward
     */
    fun moveBackward(speed: Double = 0.1): Boolean = controlManualVW(-speed, 0.0)

    /**
     * Rotate left
     */
    fun rotateLeft(speed: Double = 0.5): Boolean = controlManualVW(0.0, speed)

    /**
     * Rotate right
     */
    fun rotateRight(speed: Double = 0.5): Boolean = controlManualVW(0.0, -speed)

    /**
     * Stop movement
     */
    fun stop(): Boolean = controlManualVW(0.0, 0.0)

    // ==================== Navigation Control ====================

    /**
     * Set target position for navigation
     */
    fun setTargetPosition(x: Double, y: Double, theta: Double): Int {
        if (!checkConnection()) return -1

        return try {
            Log.d(TAG, "Setting target position: ($x, $y, $theta)")
            amrService?.opSetTargetPosition(x, y, theta, DEFAULT_DELAY) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "setTargetPosition")
            -1
        }
    }

    /**
     * Set target position with rotation type
     */
    fun setTargetPosition2(x: Double, y: Double, theta: Double, rotationType: Int): Int {
        if (!checkConnection()) return -1

        return try {
            Log.d(TAG, "Setting target position2: ($x, $y, $theta) rot=$rotationType")
            amrService?.opSetTargetPosition2(x, y, theta, rotationType, DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "setTargetPosition2")
            -1
        }
    }

    // ==================== Driving Control ====================

    /**
     * Start driving/navigation
     */
    fun startDriving(): Boolean {
        return executeCommand("startDriving") {
            amrService?.opDriving(IAMRControl.DRIVING_START, DEFAULT_DELAY)
        }
    }

    /**
     * Pause driving
     */
    fun pauseDriving(): Boolean {
        return executeCommand("pauseDriving") {
            amrService?.opDriving(IAMRControl.DRIVING_PAUSE, DEFAULT_DELAY)
        }
    }

    /**
     * Resume driving
     */
    fun resumeDriving(): Boolean {
        return executeCommand("resumeDriving") {
            amrService?.opDriving(IAMRControl.DRIVING_RESUME, DEFAULT_DELAY)
        }
    }

    /**
     * Stop driving
     */
    fun stopDriving(): Boolean {
        return executeCommand("stopDriving") {
            amrService?.opDriving(IAMRControl.DRIVING_STOP, DEFAULT_DELAY)
        }
    }

    // ==================== Mapping Control ====================

    /**
     * Start manual mapping
     */
    fun startManualMapping(): Boolean {
        return executeCommand("startManualMapping") {
            amrService?.opMapping(IAMRControl.MAPPING_START_MANUAL, DEFAULT_DELAY)
        }
    }

    /**
     * Start auto mapping
     */
    fun startAutoMapping(): Boolean {
        return executeCommand("startAutoMapping") {
            amrService?.opMapping(IAMRControl.MAPPING_START_AUTO, DEFAULT_DELAY)
        }
    }

    /**
     * Stop mapping
     */
    fun stopMapping(): Boolean {
        return executeCommand("stopMapping") {
            amrService?.opMapping(IAMRControl.MAPPING_STOP, DEFAULT_DELAY)
        }
    }

    // ==================== Rotation Control ====================

    /**
     * Rotate robot
     * @param type Rotation type (0: relative, 1: map absolute, 2: auto, 3: CCW 360, 4: CW 360)
     * @param theta Target angle
     */
    fun rotate(type: Int, theta: Double): Boolean {
        return executeCommand("rotate(type=$type, theta=$theta)") {
            amrService?.opRotate(type, theta, DEFAULT_DELAY)
        }
    }

    // ==================== Station Control ====================

    /**
     * Return to charging station
     */
    fun returnToChargingStation(): Boolean {
        return executeCommand("returnToChargingStation") {
            amrService?.opReturnToChargingStation(DEFAULT_DELAY)
        }
    }

    /**
     * Start charging
     */
    fun startCharging(): Boolean {
        return executeCommand("startCharging") {
            amrService?.opChargingBattery(IAMRControl.START_CHARGING, DEFAULT_DELAY)
        }
    }

    /**
     * Stop charging
     */
    fun stopCharging(): Boolean {
        return executeCommand("stopCharging") {
            amrService?.opChargingBattery(IAMRControl.STOP_CHARGING, DEFAULT_DELAY)
        }
    }

    /**
     * Docking
     */
    fun docking(): Int {
        if (!checkConnection()) return -1

        return try {
            Log.d(TAG, "Docking")
            amrService?.opDocking(DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "docking")
            -1
        }
    }

    // ==================== Emergency Control ====================

    /**
     * Emergency stop
     */
    fun emergencyStop(): Int {
        if (!checkConnection()) return -1

        return try {
            Log.d(TAG, "Emergency stop")
            amrService?.opEmergencyStop(DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "emergencyStop")
            -1
        }
    }

    /**
     * Reset robot
     */
    fun reset(): Boolean {
        return executeCommand("reset") {
            amrService?.opReset(DEFAULT_DELAY)
        }
    }

    // ==================== Sensor/Status Query ====================

    /**
     * Query sensor status
     */
    fun querySensor(sensorType: Int): Boolean {
        return executeCommand("querySensor(type=$sensorType)") {
            amrService?.opQuerySensor(sensorType, DEFAULT_DELAY)
        }
    }

    /**
     * Get current position
     */
    fun getCurrentPosition(): Int {
        if (!checkConnection()) return -1

        return try {
            amrService?.opGetCurrentPosition(DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "getCurrentPosition")
            -1
        }
    }

    /**
     * Get AMR version
     */
    fun getAMRVersion(): Int {
        if (!checkConnection()) return -1

        return try {
            amrService?.opGetAMRVersion(DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "getAMRVersion")
            -1
        }
    }

    /**
     * Get temperature
     */
    fun getTemperature(): Int {
        if (!checkConnection()) return -1

        return try {
            amrService?.opGetTemperature(DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "getTemperature")
            -1
        }
    }

    /**
     * Check if connected
     */
    fun isAMRConnected(): Boolean {
        if (!checkConnection()) return false

        return try {
            amrService?.opIsConnected() ?: false
        } catch (e: RemoteException) {
            handleRemoteException(e, "isAMRConnected")
            false
        }
    }

    /**
     * Check if mapping
     */
    fun isMapping(): Boolean {
        if (!checkConnection()) return false

        return try {
            amrService?.opIsMapping() ?: false
        } catch (e: RemoteException) {
            handleRemoteException(e, "isMapping")
            false
        }
    }

    /**
     * Check if navigating
     */
    fun isNavigating(): Boolean {
        if (!checkConnection()) return false

        return try {
            amrService?.opIsNavi() ?: false
        } catch (e: RemoteException) {
            handleRemoteException(e, "isNavigating")
            false
        }
    }

    // ==================== Lidar Control ====================

    /**
     * Turn lidar on
     */
    fun lidarOn(): Boolean {
        return executeCommand("lidarOn") {
            amrService?.opLidarOnOff(1, DEFAULT_DELAY)
        }
    }

    /**
     * Turn lidar off
     */
    fun lidarOff(): Boolean {
        return executeCommand("lidarOff") {
            amrService?.opLidarOnOff(4, DEFAULT_DELAY)
        }
    }

    // ==================== Follow Me ====================

    /**
     * Enable/disable follow me mode
     */
    fun setFollowMe(enable: Boolean): Int {
        if (!checkConnection()) return -1

        return try {
            Log.d(TAG, "setFollowMe: $enable")
            amrService?.opFollowMe(enable, DEFAULT_DELAY.toLong()) ?: -1
        } catch (e: RemoteException) {
            handleRemoteException(e, "setFollowMe")
            -1
        }
    }

    // ==================== Helper Methods ====================

    private fun checkConnection(): Boolean {
        if (!isBound || amrService == null) {
            Log.w(TAG, "AMR service not connected")
            onError?.invoke("AMR service not connected")
            return false
        }
        return true
    }

    private fun executeCommand(commandName: String, action: () -> Unit): Boolean {
        if (!checkConnection()) return false

        return try {
            Log.d(TAG, "Executing: $commandName")
            action()
            true
        } catch (e: RemoteException) {
            handleRemoteException(e, commandName)
            false
        }
    }

    private fun handleRemoteException(e: RemoteException, operation: String) {
        Log.e(TAG, "RemoteException in $operation", e)
        onError?.invoke("$operation failed: ${e.message}")
    }
}
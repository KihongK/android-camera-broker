package com.roy.camera_client.amr

import android.util.Log
import org.json.JSONObject

/**
 * AMR Command Handler
 * - Parses commands received from DataChannel
 * - Dispatches commands to AMRControlManager
 * - Supports JSON and simple text command formats
 *
 * Command Formats:
 * 1. JSON: {"cmd": "MOVE", "params": {"ms": 0.1, "rs": 0.0}}
 * 2. Simple: "FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP"
 * 3. With params: "MOVE:0.1,0.0" or "GOTO:1.0,2.0,0.5"
 */
class AMRCommandHandler(private val amrControlManager: AMRControlManager) {

    companion object {
        private const val TAG = "AMRCommandHandler"

        // Simple commands
        const val CMD_FORWARD = "FORWARD"
        const val CMD_BACKWARD = "BACKWARD"
        const val CMD_LEFT = "LEFT"
        const val CMD_RIGHT = "RIGHT"
        const val CMD_STOP = "STOP"
        const val CMD_EMERGENCY_STOP = "EMERGENCY_STOP"

        // Movement commands with parameters
        const val CMD_MOVE = "MOVE"           // MOVE:ms,rs
        const val CMD_GOTO = "GOTO"           // GOTO:x,y,theta
        const val CMD_ROTATE = "ROTATE"       // ROTATE:type,theta

        // Driving commands
        const val CMD_DRIVE_START = "DRIVE_START"
        const val CMD_DRIVE_PAUSE = "DRIVE_PAUSE"
        const val CMD_DRIVE_RESUME = "DRIVE_RESUME"
        const val CMD_DRIVE_STOP = "DRIVE_STOP"

        // Mapping commands
        const val CMD_MAP_MANUAL = "MAP_MANUAL"
        const val CMD_MAP_AUTO = "MAP_AUTO"
        const val CMD_MAP_STOP = "MAP_STOP"

        // Station commands
        const val CMD_RETURN_STATION = "RETURN_STATION"
        const val CMD_CHARGE_START = "CHARGE_START"
        const val CMD_CHARGE_STOP = "CHARGE_STOP"
        const val CMD_DOCKING = "DOCKING"

        // Utility commands
        const val CMD_RESET = "RESET"
        const val CMD_LIDAR_ON = "LIDAR_ON"
        const val CMD_LIDAR_OFF = "LIDAR_OFF"
        const val CMD_FOLLOW_ME = "FOLLOW_ME"  // FOLLOW_ME:true/false

        // Query commands
        const val CMD_GET_POSITION = "GET_POSITION"
        const val CMD_GET_VERSION = "GET_VERSION"
        const val CMD_GET_TEMP = "GET_TEMP"
        const val CMD_GET_STATUS = "GET_STATUS"
        const val CMD_QUERY_SENSOR = "QUERY_SENSOR"  // QUERY_SENSOR:type
    }

    /**
     * Callback for command execution results
     */
    var onCommandResult: ((command: String, success: Boolean, message: String) -> Unit)? = null

    /**
     * Handle incoming command from DataChannel
     * @return true if command was recognized and executed
     */
    fun handleCommand(command: String): Boolean {
        val trimmedCommand = command.trim()
        Log.d(TAG, "Handling command: $trimmedCommand")

        return when {
            trimmedCommand.startsWith("{") -> handleJsonCommand(trimmedCommand)
            trimmedCommand.contains(":") -> handleParameterizedCommand(trimmedCommand)
            else -> handleSimpleCommand(trimmedCommand)
        }
    }

    /**
     * Handle JSON format command
     * Example: {"cmd": "MOVE", "params": {"ms": 0.1, "rs": 0.0}}
     */
    private fun handleJsonCommand(jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val cmd = json.getString("cmd")
            val params = json.optJSONObject("params")

            when (cmd.uppercase()) {
                CMD_MOVE -> {
                    val ms = params?.optDouble("ms", 0.0) ?: 0.0
                    val rs = params?.optDouble("rs", 0.0) ?: 0.0
                    executeAndReport(cmd, amrControlManager.controlManualVW(ms, rs))
                }
                CMD_GOTO -> {
                    val x = params?.optDouble("x", 0.0) ?: 0.0
                    val y = params?.optDouble("y", 0.0) ?: 0.0
                    val theta = params?.optDouble("theta", 0.0) ?: 0.0
                    val rot = params?.optInt("rot", -1) ?: -1
                    val result = if (rot >= 0) {
                        amrControlManager.setTargetPosition2(x, y, theta, rot)
                    } else {
                        amrControlManager.setTargetPosition(x, y, theta)
                    }
                    executeAndReport(cmd, result >= 0)
                }
                CMD_ROTATE -> {
                    val type = params?.optInt("type", 0) ?: 0
                    val theta = params?.optDouble("theta", 0.0) ?: 0.0
                    executeAndReport(cmd, amrControlManager.rotate(type, theta))
                }
                CMD_FOLLOW_ME -> {
                    val enable = params?.optBoolean("enable", false) ?: false
                    executeAndReport(cmd, amrControlManager.setFollowMe(enable) >= 0)
                }
                CMD_QUERY_SENSOR -> {
                    val sensorType = params?.optInt("type", 0) ?: 0
                    executeAndReport(cmd, amrControlManager.querySensor(sensorType))
                }
                else -> handleSimpleCommand(cmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON command", e)
            reportResult("JSON_PARSE", false, "Failed to parse: ${e.message}")
            false
        }
    }

    /**
     * Handle parameterized command (e.g., "MOVE:0.1,0.0")
     */
    private fun handleParameterizedCommand(command: String): Boolean {
        val parts = command.split(":")
        if (parts.size != 2) {
            return handleSimpleCommand(command)
        }

        val cmd = parts[0].trim().uppercase()
        val paramStr = parts[1].trim()
        val params = paramStr.split(",").map { it.trim() }

        return when (cmd) {
            CMD_MOVE -> {
                if (params.size >= 2) {
                    val ms = params[0].toDoubleOrNull() ?: 0.0
                    val rs = params[1].toDoubleOrNull() ?: 0.0
                    executeAndReport(cmd, amrControlManager.controlManualVW(ms, rs))
                } else {
                    reportResult(cmd, false, "Invalid params: expected ms,rs")
                    false
                }
            }
            CMD_GOTO -> {
                if (params.size >= 3) {
                    val x = params[0].toDoubleOrNull() ?: 0.0
                    val y = params[1].toDoubleOrNull() ?: 0.0
                    val theta = params[2].toDoubleOrNull() ?: 0.0
                    val rot = if (params.size >= 4) params[3].toIntOrNull() else null
                    val result = if (rot != null) {
                        amrControlManager.setTargetPosition2(x, y, theta, rot)
                    } else {
                        amrControlManager.setTargetPosition(x, y, theta)
                    }
                    executeAndReport(cmd, result >= 0)
                } else {
                    reportResult(cmd, false, "Invalid params: expected x,y,theta")
                    false
                }
            }
            CMD_ROTATE -> {
                if (params.size >= 2) {
                    val type = params[0].toIntOrNull() ?: 0
                    val theta = params[1].toDoubleOrNull() ?: 0.0
                    executeAndReport(cmd, amrControlManager.rotate(type, theta))
                } else {
                    reportResult(cmd, false, "Invalid params: expected type,theta")
                    false
                }
            }
            CMD_FOLLOW_ME -> {
                val enable = paramStr.lowercase() == "true" || paramStr == "1"
                executeAndReport(cmd, amrControlManager.setFollowMe(enable) >= 0)
            }
            CMD_QUERY_SENSOR -> {
                val sensorType = params[0].toIntOrNull() ?: 0
                executeAndReport(cmd, amrControlManager.querySensor(sensorType))
            }
            else -> handleSimpleCommand(parts[0].trim())
        }
    }

    /**
     * Handle simple command (no parameters)
     */
    private fun handleSimpleCommand(command: String): Boolean {
        val cmd = command.uppercase()

        return when (cmd) {
            // Movement
            CMD_FORWARD -> executeAndReport(cmd, amrControlManager.moveForward())
            CMD_BACKWARD -> executeAndReport(cmd, amrControlManager.moveBackward())
            CMD_LEFT -> executeAndReport(cmd, amrControlManager.rotateLeft())
            CMD_RIGHT -> executeAndReport(cmd, amrControlManager.rotateRight())
            CMD_STOP -> executeAndReport(cmd, amrControlManager.stop())
            CMD_EMERGENCY_STOP -> executeAndReport(cmd, amrControlManager.emergencyStop() >= 0)

            // Driving
            CMD_DRIVE_START -> executeAndReport(cmd, amrControlManager.startDriving())
            CMD_DRIVE_PAUSE -> executeAndReport(cmd, amrControlManager.pauseDriving())
            CMD_DRIVE_RESUME -> executeAndReport(cmd, amrControlManager.resumeDriving())
            CMD_DRIVE_STOP -> executeAndReport(cmd, amrControlManager.stopDriving())

            // Mapping
            CMD_MAP_MANUAL -> executeAndReport(cmd, amrControlManager.startManualMapping())
            CMD_MAP_AUTO -> executeAndReport(cmd, amrControlManager.startAutoMapping())
            CMD_MAP_STOP -> executeAndReport(cmd, amrControlManager.stopMapping())

            // Station
            CMD_RETURN_STATION -> executeAndReport(cmd, amrControlManager.returnToChargingStation())
            CMD_CHARGE_START -> executeAndReport(cmd, amrControlManager.startCharging())
            CMD_CHARGE_STOP -> executeAndReport(cmd, amrControlManager.stopCharging())
            CMD_DOCKING -> executeAndReport(cmd, amrControlManager.docking() >= 0)

            // Utility
            CMD_RESET -> executeAndReport(cmd, amrControlManager.reset())
            CMD_LIDAR_ON -> executeAndReport(cmd, amrControlManager.lidarOn())
            CMD_LIDAR_OFF -> executeAndReport(cmd, amrControlManager.lidarOff())

            // Query
            CMD_GET_POSITION -> executeAndReport(cmd, amrControlManager.getCurrentPosition() >= 0)
            CMD_GET_VERSION -> executeAndReport(cmd, amrControlManager.getAMRVersion() >= 0)
            CMD_GET_TEMP -> executeAndReport(cmd, amrControlManager.getTemperature() >= 0)
            CMD_GET_STATUS -> {
                val connected = amrControlManager.isAMRConnected()
                val mapping = amrControlManager.isMapping()
                val navi = amrControlManager.isNavigating()
                reportResult(cmd, true, "connected=$connected, mapping=$mapping, navi=$navi")
                true
            }

            else -> {
                Log.w(TAG, "Unknown command: $cmd")
                reportResult(cmd, false, "Unknown command")
                false
            }
        }
    }

    private fun executeAndReport(cmd: String, success: Boolean): Boolean {
        val message = if (success) "OK" else "Failed"
        reportResult(cmd, success, message)
        return success
    }

    private fun reportResult(cmd: String, success: Boolean, message: String) {
        Log.d(TAG, "Command $cmd: success=$success, message=$message")
        onCommandResult?.invoke(cmd, success, message)
    }

    /**
     * Check if AMR service is connected
     */
    fun isServiceConnected(): Boolean = amrControlManager.isConnected()
}
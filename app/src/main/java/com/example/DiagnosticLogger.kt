package com.example

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real-time diagnostic logger for BLE, TCP Loopback Bridge, Python Reticulum, and UI actions.
 */
object DiagnosticLogger {
    private const val MAX_LOGS = 600
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @JvmStatic
    fun log(tag: String, message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$timeStamp] [$tag] $message"
        Log.d("DiagnosticLog", line)
        _logs.update { current ->
            if (current.size >= MAX_LOGS) {
                current.drop(current.size - MAX_LOGS + 1) + line
            } else {
                current + line
            }
        }
    }

    @JvmStatic
    fun logBleRawTx(bytes: ByteArray) {
        val hex = bytes.take(32).joinToString(" ") { "%02X".format(it) } + if (bytes.size > 32) "..." else ""
        log("BLE RAW", "-> TX [${bytes.size}]: $hex")
    }

    @JvmStatic
    fun logBleRawRx(bytes: ByteArray) {
        val hex = bytes.take(32).joinToString(" ") { "%02X".format(it) } + if (bytes.size > 32) "..." else ""
        log("BLE RAW", "<- RX [${bytes.size}]: $hex")
    }

    @JvmStatic
    fun logBle(message: String) {
        log("BLE", message)
    }

    @JvmStatic
    fun logSocketBridge(message: String) {
        log("SOCKET BRIDGE", message)
    }

    @JvmStatic
    fun logPythonRns(message: String) {
        log("PYTHON RNS", message)
    }

    @JvmStatic
    fun logPythonStdout(message: String) {
        AndroidLogger.logPythonStdout(message)
    }

    @JvmStatic
    fun logPythonStderr(message: String) {
        AndroidLogger.logPythonStderr(message)
    }

    @JvmStatic
    fun logUi(message: String) {
        log("UI", message)
    }

    @JvmStatic
    fun clear() {
        _logs.value = emptyList()
    }

    @JvmStatic
    fun getAllLogsText(): String {
        return _logs.value.joinToString("\n")
    }
}

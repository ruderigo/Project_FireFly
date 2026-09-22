package com.example

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton service manager guarding Reticulum and LXMF lifecycle and operations.
 */
object MeshService {
    private const val TAG = "MeshService"
    private val isRnsRunning = AtomicBoolean(false)
    private val isAnnouncing = AtomicBoolean(false)
    private var lastAnnounceTime = 0L

    @Volatile
    var rnsIdentityHash: String? = null
        private set

    private var appContext: Context? = null

    fun initContext(context: Context) {
        appContext = context.applicationContext
    }

    fun isRunning(): Boolean = isRnsRunning.get()

    /**
     * Enforce a singleton lifecycle guard around starting Reticulum and LXMF.
     * Prevents multiple engine starts and duplicate TX interfaces.
     */
    fun startReticulum() {
        val ctx = appContext
        if (ctx != null) {
            startReticulum(ctx)
        } else {
            if (!isRnsRunning.compareAndSet(false, true)) {
                Log.w("MeshService", "Reticulum is already running. Skipping duplicate start.")
                return
            }
            // Proceed with Python RNS startup if possible, otherwise reset
            isRnsRunning.set(false)
            Log.w("MeshService", "Reticulum requires context for configuration directory paths.")
        }
    }

    fun startReticulum(context: Context, displayName: String = "Android Node"): String? {
        appContext = context.applicationContext
        if (!isRnsRunning.compareAndSet(false, true)) {
            Log.w("MeshService", "Reticulum is already running. Skipping duplicate start.")
            DiagnosticLogger.logPythonRns("Reticulum is already running. Skipping duplicate start.")
            return rnsIdentityHash
        }

        return try {
            Log.i("MeshService", "Proceeding with Python RNS startup...")
            DiagnosticLogger.logPythonRns("Proceeding with Python RNS startup...")

            if (!Python.isStarted()) {
                Log.w("MeshService", "Python runtime not initialized yet.")
                isRnsRunning.set(false)
                return null
            }

            val py = Python.getInstance()
            val rnsCore = py.getModule("rns_core")
            val configDir = File(context.filesDir, "rns_config").absolutePath
            val storageDir = File(context.filesDir, "lxmf_storage").absolutePath

            val hash = rnsCore.callAttr("init_rns", configDir, storageDir, displayName)
            val hashStr = hash?.toString()
            rnsIdentityHash = hashStr
            Log.i("MeshService", "Reticulum started successfully with identity: $hashStr")
            DiagnosticLogger.logPythonRns("Reticulum started successfully: $hashStr")
            hashStr
        } catch (e: Exception) {
            Log.e("MeshService", "Failed to start Reticulum: ${e.message}", e)
            DiagnosticLogger.logPythonRns("Failed to start Reticulum: ${e.message}")
            isRnsRunning.set(false)
            null
        }
    }

    /**
     * Reset the running state if Reticulum is stopped.
     */
    fun stopReticulum() {
        isRnsRunning.set(false)
        rnsIdentityHash = null
    }

    /**
     * Broadcasts an Announce packet to the mesh, guarded against duplicate or rapid re-triggering.
     */
    fun sendAnnounce(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAnnounceTime < 1000L) {
            Log.w("MeshService", "Announce call throttled (<1000ms). Skipping duplicate TX.")
            return false
        }
        if (!isAnnouncing.compareAndSet(false, true)) {
            Log.w("MeshService", "Announce broadcast already in progress. Skipping duplicate start.")
            return false
        }
        return try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val res = py.getModule("rns_core").callAttr("announce_presence")
                lastAnnounceTime = System.currentTimeMillis()
                Log.i("MeshService", "Announce broadcasted to mesh via rns_core.")
                DiagnosticLogger.logPythonRns("Announce broadcasted to mesh.")
                res?.toBoolean() ?: true
            } else {
                Log.w("MeshService", "Cannot announce: Python not started.")
                false
            }
        } catch (e: Exception) {
            Log.e("MeshService", "Announce error: ${e.message}", e)
            false
        } finally {
            isAnnouncing.set(false)
        }
    }

    private val isBleConnecting = AtomicBoolean(false)

    /**
     * Mutex guard around BLE connection lifecycle to prevent duplicate auto-reconnect triggers.
     */
    fun connectBle(device: android.bluetooth.BluetoothDevice, connectAction: (android.bluetooth.BluetoothDevice) -> Unit) {
        if (!isBleConnecting.compareAndSet(false, true)) {
            Log.w("MeshService", "BLE connection already in progress. Ignoring duplicate request.")
            DiagnosticLogger.logBle("Connection already in progress. Ignoring duplicate request.")
            return
        }
        try {
            connectAction(device)
        } finally {
            isBleConnecting.set(false)
        }
    }
}

/**
 * Backward compatibility alias for PythonManager.
 */
typealias PythonManager = MeshService

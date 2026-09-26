package com.example

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.atomic.AtomicBoolean

object ReticulumBridge {
    private const val TAG = "ReticulumBridge"
    private val isStarted = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (!isStarted.compareAndSet(false, true)) {
            Log.d(TAG, "ReticulumBridge already initialized. Suppressing secondary startup call.")
            return
        }

        try {
            Log.i(TAG, "Booting primary Reticulum engine...")
            DiagnosticLogger.logPythonRns("Booting primary Reticulum engine...")
            val appContext = context.applicationContext
            if (!Python.isStarted()) {
                try {
                    Log.i(TAG, "Starting Chaquopy Python runtime...")
                    Python.start(AndroidPlatform(appContext))
                    Log.i(TAG, "Chaquopy Python runtime started.")
                } catch (t: Throwable) {
                    Log.w(TAG, "Chaquopy runtime start note: ${t.message}")
                }
            }
            MeshService.initContext(appContext)
            MeshService.markBackendReady()
            try {
                val hash = MeshService.startReticulum(appContext)
                Log.i(TAG, "Reticulum engine active: $hash")
            } catch (t: Throwable) {
                Log.w(TAG, "Reticulum start note: ${t.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Reticulum engine: ${e.message}", e)
            DiagnosticLogger.logPythonRns("Failed to initialize Reticulum backend: ${e.message}")
        }
    }
}

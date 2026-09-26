package com.example

/**
 * AndroidLogger provides static methods for Chaquopy Python sys.stdout and sys.stderr redirection.
 */
object AndroidLogger {
    @JvmStatic
    fun logPythonStdout(message: String) {
        val clean = message.trim()
        if (clean.isNotEmpty()) {
            DiagnosticLogger.logPythonRns(clean)
        }
    }

    @JvmStatic
    fun logPythonStderr(message: String) {
        val clean = message.trim()
        if (clean.isNotEmpty()) {
            DiagnosticLogger.log("PYTHON RNS ERR", clean)
        }
    }
}

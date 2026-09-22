package com.example

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton TCP loopback server listening on 127.0.0.1:4243.
 * Interfaces Reticulum Network Stack (Python) to the active RadioTransport (USB CDC-ACM or BLE NUS).
 */
object RadioBridgeServer {
    private const val TAG = "RadioBridgeServer"
    const val PORT = 4243
    private const val KISS_FEND: Byte = 0xC0.toByte()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isListening = AtomicBoolean(false)
    private val serverLock = Any()
    private val clientLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClientSocket: Socket? = null
    private var activeClientJob: Job? = null

    val rxPackets = AtomicLong(0)
    val txPackets = AtomicLong(0)
    val lastTxTime = AtomicLong(0)

    // Dynamic delegate provider for the active radio transport
    @Volatile
    var transportDelegate: RadioBridgeTransportDelegate? = null

    interface RadioBridgeTransportDelegate {
        fun sendBytes(data: ByteArray): Boolean
        fun getIncomingFlow(): Flow<ByteArray>
        fun isBle(): Boolean
        fun logToRns(message: String)
        fun setLastError(error: String?)
    }

    fun isServerRunning(): Boolean {
        return isListening.get() && serverSocket?.isBound == true && serverSocket?.isClosed == false
    }

    fun closeActiveClient() {
        synchronized(clientLock) {
            try {
                activeClientJob?.cancel()
                activeClientSocket?.close()
            } catch (e: Exception) {}
            activeClientSocket = null
        }
    }

    /**
     * Start the single ServerSocket(4243).
     * Guarded with an atomic check and mutex so multiple calls never re-bind or start duplicate server threads.
     */
    fun startServer() {
        synchronized(serverLock) {
            val currentServer = serverSocket
            if (isListening.get() && currentServer != null && currentServer.isBound && !currentServer.isClosed) {
                Log.d(TAG, "RadioBridgeServer already listening on 127.0.0.1:$PORT (singleton guarded)")
                return
            }
            if (isListening.compareAndSet(false, true)) {
                startListeningLoop()
            }
        }
    }

    private fun startListeningLoop() {
        scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", PORT))
                }
                serverSocket = ss
                val bindMsg = "TCP Loopback Server listening on 127.0.0.1:$PORT"
                Log.i(TAG, bindMsg)
                transportDelegate?.logToRns(bindMsg)
                DiagnosticLogger.logSocketBridge("Bridge socket bound on 127.0.0.1:$PORT")

                while (isActive && !ss.isClosed) {
                    val incomingSocket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!isActive || ss.isClosed) break
                        delay(200)
                        continue
                    }

                    // Python connected or reconnected. Close previous client socket cleanly before accepting new one.
                    synchronized(clientLock) {
                        try {
                            activeClientJob?.cancel()
                            activeClientSocket?.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing previous client socket: ${e.message}")
                        }
                        activeClientSocket = incomingSocket
                    }

                    try {
                        incomingSocket.tcpNoDelay = true
                    } catch (e: Exception) {}

                    val connectMsg = "[SOCKET BRIDGE] Client connected from 127.0.0.1"
                    Log.i(TAG, connectMsg)
                    transportDelegate?.logToRns(connectMsg)
                    DiagnosticLogger.logSocketBridge("Client connected from 127.0.0.1")

                    synchronized(clientLock) {
                        activeClientJob = scope.launch(Dispatchers.IO) {
                            handleClientConnection(incomingSocket)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal TCP Server bind/accept error: ${e.message}", e)
                transportDelegate?.logToRns("TCP Server error: ${e.message}")
                DiagnosticLogger.logSocketBridge("TCP Server error: ${e.message}")
            } finally {
                isListening.set(false)
                closeQuietly(serverSocket)
                serverSocket = null
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = coroutineScope {
        var jobOutbound: Job? = null
        var jobInbound: Job? = null

        jobInbound = launch(Dispatchers.IO) {
            try {
                pipeRadioToTcp(socket)
            } finally {
                closeQuietly(socket)
                jobOutbound?.cancel()
            }
        }

        jobOutbound = launch(Dispatchers.IO) {
            try {
                pipeTcpToRadio(socket)
            } finally {
                closeQuietly(socket)
                jobInbound?.cancel()
            }
        }

        try {
            joinAll(jobInbound, jobOutbound)
        } catch (e: Exception) {
            // Cancelled or socket closed
        } finally {
            closeQuietly(socket)
            synchronized(clientLock) {
                if (activeClientSocket == socket) {
                    activeClientSocket = null
                }
            }
            val disconnectMsg = "[SOCKET BRIDGE] Client disconnected from 127.0.0.1"
            Log.i(TAG, disconnectMsg)
            transportDelegate?.logToRns(disconnectMsg)
            DiagnosticLogger.logSocketBridge("Client disconnected from 127.0.0.1")
        }
    }

    private suspend fun pipeRadioToTcp(socket: Socket) = withContext(Dispatchers.IO) {
        val os = try {
            socket.getOutputStream()
        } catch (e: Exception) {
            return@withContext
        }

        val incomingFlow = transportDelegate?.getIncomingFlow() ?: emptyFlow()
        try {
            incomingFlow.collect { chunk ->
                if (socket.isClosed || !socket.isConnected) {
                    throw CancellationException("Socket is closed")
                }
                for (b in chunk) {
                    if (b == KISS_FEND) rxPackets.incrementAndGet()
                }
                val isBle = transportDelegate?.isBle() ?: true
                val prefix = if (isBle) "BLE RX" else "USB RX"
                val inMsg = "[SOCKET BRIDGE] Inbound data: ${chunk.size} bytes -> Python"
                transportDelegate?.logToRns("[$prefix]: ${chunk.size} bytes")
                transportDelegate?.logToRns(inMsg)
                DiagnosticLogger.logSocketBridge("Inbound data: ${chunk.size} bytes -> Python")
                try {
                    os.write(chunk)
                    os.flush()
                } catch (e: Exception) {
                    throw CancellationException("Socket write failed: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            // flow ended or socket closed
        }
    }

    private suspend fun pipeTcpToRadio(socket: Socket) = withContext(Dispatchers.IO) {
        val inputStream = try {
            socket.getInputStream()
        } catch (e: Exception) {
            return@withContext
        }

        val buf = ByteArray(1024)
        while (isActive && !socket.isClosed && socket.isConnected) {
            try {
                val len = inputStream.read(buf)
                if (len > 0) {
                    val chunk = buf.copyOf(len)
                    for (b in chunk) {
                        if (b == KISS_FEND) txPackets.incrementAndGet()
                    }
                    val isBle = transportDelegate?.isBle() ?: true
                    val prefix = if (isBle) "BLE TX" else "USB TX"
                    val success = transportDelegate?.sendBytes(chunk) ?: false
                    if (success) {
                        lastTxTime.set(System.currentTimeMillis())
                        val outMsg = "[SOCKET BRIDGE] Outbound data: ${chunk.size} bytes -> Radio"
                        transportDelegate?.logToRns("[$prefix]: ${chunk.size} bytes")
                        transportDelegate?.logToRns(outMsg)
                        DiagnosticLogger.logSocketBridge("Outbound data: ${chunk.size} bytes -> Radio")
                        transportDelegate?.setLastError(null)
                    } else {
                        val errMsg = "Failed to send packet over $prefix (not connected)"
                        transportDelegate?.setLastError(errMsg)
                        DiagnosticLogger.log("ERROR", errMsg)
                    }
                } else if (len == -1) {
                    // Python closed stream (EOF)
                    break
                }
            } catch (e: Exception) {
                if (socket.isClosed) break
                delay(50)
            }
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (e: Exception) {}
    }

    private fun closeQuietly(serverSocket: ServerSocket?) {
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
    }

    fun stopServer() {
        synchronized(serverLock) {
            isListening.set(false)
            synchronized(clientLock) {
                activeClientJob?.cancel()
                closeQuietly(activeClientSocket)
                activeClientSocket = null
            }
            closeQuietly(serverSocket)
            serverSocket = null
        }
    }
}

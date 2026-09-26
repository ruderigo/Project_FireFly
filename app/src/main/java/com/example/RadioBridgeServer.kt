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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    private val activeClientSocketRef = AtomicReference<Socket?>(null)
    private val activeWorkerJob = AtomicReference<Job?>(null)
    private val currentWorkerId = AtomicLong(0)

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

    private val workerMutex = Mutex()
    private val flowCollectorMutex = Mutex()

    fun isServerRunning(): Boolean {
        return isListening.get() && serverSocket?.isBound == true && serverSocket?.isClosed == false
    }

    fun isClientConnected(): Boolean {
        val s = activeClientSocketRef.get()
        return s != null && s.isConnected && !s.isClosed
    }

    suspend fun terminateActiveClient() {
        currentWorkerId.incrementAndGet()
        val sock = activeClientSocketRef.getAndSet(null)
        val job = activeWorkerJob.getAndSet(null)

        try {
            try { sock?.shutdownInput() } catch (e: Exception) {}
            try { sock?.shutdownOutput() } catch (e: Exception) {}
            try { sock?.close() } catch (e: Exception) {}
            job?.cancel()
            job?.join()
        } catch (e: Exception) {
            Log.w(TAG, "Error joining previous worker: ${e.message}")
        }
    }

    fun terminateActiveWorker() {
        currentWorkerId.incrementAndGet()
        val sock = activeClientSocketRef.getAndSet(null)
        val job = activeWorkerJob.getAndSet(null)
        try {
            try { sock?.shutdownInput() } catch (e: Exception) {}
            try { sock?.shutdownOutput() } catch (e: Exception) {}
            try { sock?.close() } catch (e: Exception) {}
            job?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error terminating active worker: ${e.message}")
        }
    }

    fun terminateActiveClientBlocking() {
        terminateActiveWorker()
        scope.launch(Dispatchers.IO) {
            try {
                terminateActiveClient()
            } catch (e: Exception) {
                Log.w(TAG, "Error in terminateActiveClient async: ${e.message}")
            }
        }
    }

    fun closeActiveClient() {
        terminateActiveWorker()
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

                    // Enforce strict singleton reader worker: explicitly cancel, close, and join any existing worker
                    terminateActiveClient()

                    val workerId = currentWorkerId.get()
                    try {
                        incomingSocket.tcpNoDelay = true
                    } catch (e: Exception) {}

                    val connectMsg = "[SOCKET BRIDGE] Client connected from 127.0.0.1 (worker #$workerId)"
                    Log.i(TAG, connectMsg)
                    transportDelegate?.logToRns(connectMsg)
                    DiagnosticLogger.logSocketBridge("Client connected from 127.0.0.1 (worker #$workerId)")

                    activeClientSocketRef.set(incomingSocket)
                    val job = scope.launch(Dispatchers.IO) {
                        handleClientConnection(incomingSocket, workerId)
                    }
                    activeWorkerJob.set(job)
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

    private suspend fun handleClientConnection(socket: Socket, workerId: Long) {
        workerMutex.withLock {
            coroutineScope {
                var jobOutbound: Job? = null
                var jobInbound: Job? = null

                jobInbound = launch(Dispatchers.IO) {
                    try {
                        pipeRadioToTcp(socket, workerId)
                    } finally {
                        closeQuietly(socket)
                        jobOutbound?.cancel()
                    }
                }

                jobOutbound = launch(Dispatchers.IO) {
                    try {
                        pipeTcpToRadio(socket, workerId)
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
                    activeClientSocketRef.compareAndSet(socket, null)
                    val disconnectMsg = "[SOCKET BRIDGE] Client disconnected from 127.0.0.1 (worker #$workerId)"
                    Log.i(TAG, disconnectMsg)
                    transportDelegate?.logToRns(disconnectMsg)
                    DiagnosticLogger.logSocketBridge("Client disconnected from 127.0.0.1 (worker #$workerId)")
                }
            }
        }
    }

    private suspend fun pipeRadioToTcp(socket: Socket, workerId: Long) = withContext(Dispatchers.IO) {
        flowCollectorMutex.withLock {
            val os = try {
                socket.getOutputStream()
            } catch (e: Exception) {
                return@withContext
            }

            val incomingFlow = transportDelegate?.getIncomingFlow() ?: emptyFlow()
            try {
                incomingFlow.collect { chunk ->
                    if (workerId != currentWorkerId.get() || !isActive || socket.isClosed || !socket.isConnected) {
                        throw CancellationException("Worker #$workerId superseded or socket closed")
                    }
                    for (b in chunk) {
                        if (b == KISS_FEND) rxPackets.incrementAndGet()
                    }
                    val isBle = transportDelegate?.isBle() ?: true
                    val prefix = if (isBle) "BLE RX" else "USB RX"
                    val inMsg = "[SOCKET BRIDGE] Inbound data: ${chunk.size} bytes -> Python (w#$workerId)"
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
    }

    private suspend fun pipeTcpToRadio(socket: Socket, workerId: Long) = withContext(Dispatchers.IO) {
        val inputStream = try {
            socket.getInputStream()
        } catch (e: Exception) {
            return@withContext
        }

        val buf = ByteArray(1024)
        while (isActive && !socket.isClosed && socket.isConnected && workerId == currentWorkerId.get()) {
            val len = try {
                inputStream.read(buf)
            } catch (e: Exception) {
                break
            }
            if (workerId != currentWorkerId.get() || len <= 0) break
            val chunk = buf.copyOf(len)
            for (b in chunk) {
                if (b == KISS_FEND) txPackets.incrementAndGet()
            }
            val isBle = transportDelegate?.isBle() ?: true
            val prefix = if (isBle) "BLE TX" else "USB TX"
            val success = transportDelegate?.sendBytes(chunk) ?: false
            if (success) {
                lastTxTime.set(System.currentTimeMillis())
                val outMsg = "[SOCKET BRIDGE] Outbound data: ${chunk.size} bytes -> Radio (w#$workerId)"
                transportDelegate?.logToRns("[$prefix]: ${chunk.size} bytes")
                transportDelegate?.logToRns(outMsg)
                DiagnosticLogger.logSocketBridge("Outbound data: ${chunk.size} bytes -> Radio")
                transportDelegate?.setLastError(null)
            } else {
                val errMsg = "Failed to send packet over $prefix (not connected)"
                transportDelegate?.setLastError(errMsg)
                DiagnosticLogger.log("ERROR", errMsg)
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
            terminateActiveWorker()
            closeQuietly(serverSocket)
            serverSocket = null
        }
    }
}

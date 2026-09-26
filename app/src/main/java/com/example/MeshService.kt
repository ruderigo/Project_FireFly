package com.example

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val _isBackendReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isBackendReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _isBackendReady.asStateFlow()

    fun markBackendReady() {
        _isBackendReady.value = true
    }

    fun isBackendReady(): Boolean = _isBackendReady.value && Python.isStarted()

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
        if (rnsIdentityHash != null) {
            Log.w("MeshService", "Reticulum is already running with identity: $rnsIdentityHash. Skipping duplicate start.")
            return rnsIdentityHash
        }
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

            val prefs = context.getSharedPreferences("rns_prefs", Context.MODE_PRIVATE)
            val txPower = prefs.getInt("tx_power", 17)

            val hash = rnsCore.callAttr("init_rns", configDir, storageDir, displayName, txPower)
            val hashStr = hash?.toString()
            rnsIdentityHash = hashStr
            markBackendReady()
            Log.i("MeshService", "Reticulum started successfully with identity: $hashStr (TX $txPower dBm)")
            DiagnosticLogger.logPythonRns("Reticulum started successfully: $hashStr (TX $txPower dBm)")
            hashStr
        } catch (e: Exception) {
            Log.e("MeshService", "Failed to start Reticulum: ${e.message}", e)
            DiagnosticLogger.logPythonRns("Failed to start Reticulum: ${e.message}")
            isRnsRunning.set(false)
            null
        }
    }

    /**
     * Dynamically updates Reticulum RNode TX power via Python bridge set_radio_tx_power(tx_power_dbm).
     */
    fun setPythonRadioTxPower(powerDbm: Int): Int {
        return try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val rnsCore = py.getModule("rns_core")
                val res = rnsCore.callAttr("set_radio_tx_power", powerDbm)
                res.toInt()
            } else {
                powerDbm
            }
        } catch (e: Exception) {
            Log.e("MeshService", "Failed to set Python radio tx power: ${e.message}", e)
            powerDbm
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

    /**
     * Notify Python backend that the RNode radio interface has finished initializing
     * and transitioned to active. Triggers the automated startup announce (after SX1262
     * register stabilization delay) and starts the periodic keep-alive background timer.
     */
    fun notifyInterfaceActive() {
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                py.getModule("rns_core").callAttr("notify_interface_active")
                Log.i(TAG, "Notified Python backend: RNode interface is ACTIVE")
                DiagnosticLogger.logPythonRns("RNode interface online. Auto-announce scheduled.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify interface active: ${e.message}")
        }
    }

    @JvmStatic
    fun registerStumpHash(hash: String) {
        com.example.data.DirectMessageRepository.registerStumpHash(hash)
    }

    fun isStumpHash(hash: String): Boolean {
        return com.example.data.DirectMessageRepository.isStumpHash(hash)
    }

    fun resolveLiveStumpHash(): String? {
        return try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val res = py.getModule("rns_core").callAttr("get_live_stump_hash")?.toString()
                if (!res.isNullOrBlank() && res != "None") res else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private var dmRepository: com.example.data.DirectMessageRepository? = null

    fun setDmRepository(repository: com.example.data.DirectMessageRepository) {
        dmRepository = repository
    }

    fun getDmRepository(): com.example.data.DirectMessageRepository? = dmRepository

    fun getOrInitRepository(): com.example.data.DirectMessageRepository? {
        if (dmRepository != null) return dmRepository
        val ctx = appContext
        if (ctx != null) {
            val db = com.example.data.AppDatabase.getDatabase(ctx)
            val repo = com.example.data.DirectMessageRepository(db.directMessageDao())
            dmRepository = repo
            return repo
        }
        return null
    }

    /**
     * Called directly from Python Reticulum delivery_callback when a direct message arrives.
     * Inserts the message into Room DB and notifies active conversation StateFlow immediately.
     */
    @JvmStatic
    @JvmOverloads
    fun onIncomingDirectMessage(
        senderHash: String,
        content: String,
        isDirectLink: Boolean = false,
        messageHash: String = "",
        isPrivate: Boolean = true
    ) {
        try {
            val cleanSender = com.example.data.DirectMessageRepository.normalizeHash(senderHash)
            val now = System.currentTimeMillis()
            val myHash = com.example.data.DirectMessageRepository.normalizeHash(rnsIdentityHash ?: "")
            val stableId = if (messageHash.isNotBlank()) {
                messageHash.trim().lowercase()
            } else {
                "${cleanSender}_${content.hashCode()}_${now}_${(Math.random() * 10000).toInt()}"
            }
            val statusLabel = if (isPrivate || isDirectLink) "Direct Message" else "Delivered"
            val entity = com.example.data.DirectMessageEntity(
                id = stableId,
                peerHash = cleanSender,
                senderHash = cleanSender,
                recipientHash = myHash,
                content = content,
                isIncoming = true,
                status = statusLabel,
                timestamp = now,
                isDirectLink = isDirectLink
            )
            val repo = getOrInitRepository()
            repo?.let { r ->
                val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                    Log.e(TAG, "Coroutine exception inserting incoming DM: ${throwable.message}", throwable)
                }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + exceptionHandler).launch {
                    try {
                        r.insertMessage(entity)
                        Log.d(TAG, "Incoming DM persisted into Room for peer $cleanSender: $content (id=$stableId, status=$statusLabel)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to insert incoming DM: ${e.message}", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error in onIncomingDirectMessage: ${e.message}", e)
        }
    }

    /**
     * Probes peer with a lightweight link to flush pending delivery queues in Reticulum.
     */
    fun syncPeer(peerHash: String): Boolean {
        val cleanPeer = com.example.data.DirectMessageRepository.normalizeHash(peerHash)
        return try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                py.getModule("rns_core").callAttr("sync_peer", cleanPeer)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPeer: ${e.message}")
            false
        }
    }

    fun sendDirectMessage(peerHash: String, content: String): Boolean {
        val cleanPeer = com.example.data.DirectMessageRepository.normalizeHash(peerHash)
        if (cleanPeer.isBlank()) return false
        return try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val res = py.getModule("rns_core").callAttr("send_message", cleanPeer, content)
                val success = res?.toBoolean() ?: false
                if (success) {
                    val now = System.currentTimeMillis()
                    val myHash = com.example.data.DirectMessageRepository.normalizeHash(rnsIdentityHash ?: "")
                    val stableId = "out_${cleanPeer}_${content.hashCode()}_$now"
                    val entity = com.example.data.DirectMessageEntity(
                        id = stableId,
                        peerHash = cleanPeer,
                        senderHash = myHash,
                        recipientHash = cleanPeer,
                        content = content,
                        isIncoming = false,
                        status = "Sent",
                        timestamp = now,
                        isDirectLink = false
                    )
                    dmRepository?.let { repo ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try { repo.insertMessage(entity) } catch (_: Exception) {}
                        }
                    }
                }
                success
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending standard peer DM: ${e.message}")
            false
        }
    }

    /**
     * Notify Python backend that the RNode radio interface has disconnected.
     * Pauses the periodic keep-alive timer and tears down the socket reader cleanly.
     */
    fun notifyInterfaceInactive() {
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                py.getModule("rns_core").callAttr("notify_interface_inactive")
                Log.i(TAG, "Notified Python backend: RNode interface is INACTIVE")
                DiagnosticLogger.logPythonRns("RNode interface offline.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify interface inactive: ${e.message}")
        }
    }
}

/**
 * Backward compatibility alias for PythonManager.
 */
typealias PythonManager = MeshService

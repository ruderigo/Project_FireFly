package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

enum class BridgeState {
    DISCONNECTED, ATTACHED, ONLINE, RF_TRANSMITTING
}

enum class TransportType {
    NONE, USB, BLE
}

class UsbRNodeBridge(private val context: Context) {

    companion object {
        private const val TAG = "UsbRNodeBridge"
        private const val TCP_PORT = 4243
        private const val PREFS_NAME = "rnode_radio_prefs"
        private const val KEY_LAST_BLE_MAC = "last_ble_mac"
        private const val KEY_LAST_BLE_NAME = "last_ble_name"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val usbTransport = UsbRadioTransport(context) { logToRns(it) }
    val bleTransport = BleRadioTransport(context) { logToRns(it) }

    var activeTransport: RadioTransport? = null
        private set

    private val _activeTransportFlow = MutableStateFlow<RadioTransport?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeIncomingFlow: Flow<ByteArray> = _activeTransportFlow
        .filterNotNull()
        .flatMapLatest { transport -> transport.incomingBytes }

    private val _activeTransportType = MutableStateFlow(TransportType.NONE)
    val activeTransportType: StateFlow<TransportType> = _activeTransportType.asStateFlow()

    private val _bridgeState = MutableStateFlow(BridgeState.DISCONNECTED)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val txPackets: AtomicLong
        get() = RadioBridgeServer.txPackets
    val rxPackets: AtomicLong
        get() = RadioBridgeServer.rxPackets
    private val lastTxTime: AtomicLong
        get() = RadioBridgeServer.lastTxTime

    private var autoReconnectJob: Job? = null

    // KISS framing constants
    private val KISS_FEND: Byte = 0xC0.toByte()
    private val KISS_FESC: Byte = 0xDB.toByte()
    private val KISS_TFEND: Byte = 0xDC.toByte()
    private val KISS_TFESC: Byte = 0xDD.toByte()

    // RNode KISS commands
    private val CMD_FREQUENCY: Byte = 0x01.toByte()
    private val CMD_BANDWIDTH: Byte = 0x02.toByte()
    private val CMD_TXPOWER: Byte = 0x03.toByte()
    private val CMD_SF: Byte = 0x04.toByte()
    private val CMD_CR: Byte = 0x05.toByte()
    private val CMD_RADIO_STATE: Byte = 0x06.toByte()
    private val CMD_DETECT: Byte = 0x0B.toByte()
    private val CMD_STAT_INTERVAL: Byte = 0x51.toByte()

    val lastSavedBleMac: String?
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_BLE_MAC, null)

    val lastSavedBleName: String?
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_BLE_NAME, null)

    fun isConnected(): Boolean {
        val s = _bridgeState.value
        return s == BridgeState.ONLINE || s == BridgeState.RF_TRANSMITTING
    }

    fun isUsbDevicePluggedIn(): Boolean {
        return usbManager.deviceList.isNotEmpty()
    }

    fun activeNodeDescriptor(): String {
        return when (_activeTransportType.value) {
            TransportType.USB -> "Heltec V3 (USB)"
            TransportType.BLE -> bleTransport.connectedDeviceName ?: "Heltec V3 (BLE)"
            TransportType.NONE -> "No Node"
        }
    }

    private fun logToRns(msg: String) {
        Log.d(TAG, msg)
        try {
            if (com.chaquo.python.Python.isStarted()) {
                val py = com.chaquo.python.Python.getInstance()
                py.getModule("rns_core").callAttr("log_status", msg)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (_activeTransportType.value == TransportType.USB) {
                    logToRns("USB Device detached. Disconnecting USB transport.")
                    disconnect()
                }
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == UsbRadioTransport.ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    logToRns("USB Permission granted for ${device.deviceName}")
                    connectUsb(device)
                } else {
                    logToRns("USB Permission denied for ${device?.deviceName}")
                }
            }
        }
    }

    init {
        try {
            val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                detachReceiver,
                detachFilter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )

            val permFilter = IntentFilter(UsbRadioTransport.ACTION_USB_PERMISSION)
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                permissionReceiver,
                permFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Monitor USB transport connection state
        scope.launch {
            usbTransport.isConnected.collectLatest { connected ->
                if (_activeTransportType.value == TransportType.USB) {
                    if (connected) {
                        onTransportEstablished(usbTransport, TransportType.USB)
                    } else if (_bridgeState.value != BridgeState.DISCONNECTED) {
                        logToRns("USB Transport disconnected.")
                        onTransportLost()
                    }
                }
            }
        }

        // Monitor BLE transport connection state
        scope.launch {
            bleTransport.isConnected.collectLatest { connected ->
                if (_activeTransportType.value == TransportType.BLE) {
                    if (connected) {
                        onTransportEstablished(bleTransport, TransportType.BLE)
                    } else if (_bridgeState.value != BridgeState.DISCONNECTED) {
                        logToRns("BLE Transport disconnected.")
                        onTransportLost()
                    }
                }
            }
        }
    }

    // --- Connect methods ---

    fun startServerAndConnectUsb() {
        startTcpListener()
        connectUsb()
    }

    fun connectUsb(device: UsbDevice? = null) {
        if (_activeTransportType.value == TransportType.BLE) {
            logToRns("Switching transport: Disconnecting BLE before connecting USB...")
            bleTransport.disconnect()
        }
        _activeTransportType.value = TransportType.USB
        activeTransport = usbTransport
        _activeTransportFlow.value = usbTransport
        _bridgeState.value = BridgeState.ATTACHED
        logToRns("Connecting USB Radio Transport...")
        usbTransport.connect(device ?: Unit)
    }

    fun connectBle(addressOrDevice: Any) {
        if (_activeTransportType.value == TransportType.USB) {
            logToRns("Switching transport: Disconnecting USB before connecting BLE...")
            usbTransport.disconnect()
        }
        _activeTransportType.value = TransportType.BLE
        activeTransport = bleTransport
        _activeTransportFlow.value = bleTransport
        _bridgeState.value = BridgeState.ATTACHED
        logToRns("Connecting BLE Radio Transport...")
        bleTransport.connect(addressOrDevice)
    }

    fun saveBlePreference(mac: String, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_BLE_MAC, mac)
            .putString(KEY_LAST_BLE_NAME, name)
            .apply()
    }

    fun autoReconnectBleIfAvailable(timeoutMs: Long = 5000L): Boolean {
        val lastMac = lastSavedBleMac ?: return false
        if (!bleTransport.isBluetoothEnabled) return false
        logToRns("Auto-reconnecting to last saved BLE node: $lastMac (timeout: ${timeoutMs}ms)")

        autoReconnectJob?.cancel()
        connectBle(lastMac)

        autoReconnectJob = scope.launch {
            delay(timeoutMs)
            if (_bridgeState.value != BridgeState.ONLINE && _bridgeState.value != BridgeState.RF_TRANSMITTING) {
                logToRns("Auto-reconnect to $lastMac timed out after 5s. Aborting cleanly to DISCONNECTED.")
                disconnect()
            }
        }
        return true
    }

    fun forgetBleDevice() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_BLE_MAC)
            .remove(KEY_LAST_BLE_NAME)
            .apply()
        disconnect()
        logToRns("Forgot cached BLE node.")
    }

    private fun onTransportEstablished(transport: RadioTransport, type: TransportType) {
        scope.launch(Dispatchers.IO) {
            try {
                autoReconnectJob?.cancel()
                autoReconnectJob = null

                // Save BLE device preferences on successful connection
                if (type == TransportType.BLE) {
                    bleTransport.connectedMacAddress?.let { mac ->
                        val name = bleTransport.connectedDeviceName ?: "Heltec RNode"
                        saveBlePreference(mac, name)
                    }
                }

                // Start Foreground service for wakelocks
                try {
                    RnsNodeService.start(context)
                } catch (e: Throwable) {
                    // ignore
                }

                _bridgeState.value = BridgeState.ATTACHED
                activeTransport = transport
                _activeTransportFlow.value = transport
                val prefix = if (type == TransportType.USB) "USB" else "BLE"
                logToRns("$prefix Attached: Handshaking with Heltec RNode firmware...")

                val confirmed = if (type == TransportType.BLE) {
                    // BleRadioTransport.onDescriptorWrite already sends the 6 KISS wake/config frames:
                    // Freq 915M, BW 125k, SF8, CR 4/5, TX 7dBm, State RX 0x01
                    true
                } else {
                    initRNodeModem(transport)
                }

                if (confirmed) {
                    // Force Python Reticulum to establish a clean loopback pipe if needed
                    RadioBridgeServer.closeActiveClient()
                    startTcpListener()
                    _bridgeState.value = BridgeState.ONLINE
                    _lastError.value = null
                    logToRns("RNode Bridge: ONLINE ($prefix 915.0 MHz)")
                } else {
                    _bridgeState.value = BridgeState.ATTACHED
                    logToRns("RNode Bridge: Modem configuration sent, remaining in ATTACHED.")
                }
            } catch (e: Exception) {
                logToRns("Transport establishment error: ${e.message}")
            }
        }
    }

    private fun onTransportLost() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        _bridgeState.value = BridgeState.DISCONNECTED
        RadioBridgeServer.closeActiveClient()
        logToRns("Transport disconnected. Closed loopback socket feeding Python.")
    }

    // --- RNode Modem KISS Configuration ---

    private fun escapeKiss(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 8)
        for (b in data) {
            when (b) {
                KISS_FEND -> {
                    out.add(KISS_FESC)
                    out.add(KISS_TFEND)
                }
                KISS_FESC -> {
                    out.add(KISS_FESC)
                    out.add(KISS_TFESC)
                }
                else -> out.add(b)
            }
        }
        return out.toByteArray()
    }

    private fun sendKiss(transport: RadioTransport, cmd: Byte, data: ByteArray = byteArrayOf()) {
        val escaped = escapeKiss(data)
        val frame = byteArrayOf(KISS_FEND, cmd) + escaped + byteArrayOf(KISS_FEND)
        transport.sendBytes(frame)
        for (b in frame) {
            if (b == KISS_FEND) txPackets.incrementAndGet()
        }
        lastTxTime.set(System.currentTimeMillis())
    }

    private fun initRNodeModem(transport: RadioTransport): Boolean {
        try {
            return RNodeRadioConfig.sendInitSequence(transport) { logToRns(it) }
        } catch (e: Exception) {
            logToRns("RNode Init Error: ${e.message}")
            return false
        }
    }

    init {
        RadioBridgeServer.transportDelegate = object : RadioBridgeServer.RadioBridgeTransportDelegate {
            override fun sendBytes(data: ByteArray): Boolean {
                val transport = activeTransport ?: return false
                if (!isConnected()) return false
                return transport.sendBytes(data)
            }

            override fun getIncomingFlow(): Flow<ByteArray> {
                return activeIncomingFlow
            }

            override fun isBle(): Boolean {
                return _activeTransportType.value == TransportType.BLE
            }

            override fun logToRns(message: String) {
                this@UsbRNodeBridge.logToRns(message)
            }

            override fun setLastError(error: String?) {
                _lastError.value = error
            }
        }
        startTcpListener()
    }

    // --- TCP Loopback Server (Delegated to RadioBridgeServer Singleton on 127.0.0.1:4243) ---

    fun startTcpListener() {
        RadioBridgeServer.startServer()
    }

    fun manualReinitRadio() {
        val transport = activeTransport
        if (transport != null) {
            logToRns("Manual Re-init Radio requested by user...")
            DiagnosticLogger.logUi("Manual Re-init Radio triggered by user (6 RNode KISS frames)")
            scope.launch(Dispatchers.IO) {
                if (_activeTransportType.value == TransportType.BLE) {
                    (transport as? BleRadioTransport)?.wakeAndConfigureRNode() ?: initRNodeModem(transport)
                } else {
                    initRNodeModem(transport)
                }
            }
        } else {
            logToRns("Manual Re-init failed: No radio connected")
            DiagnosticLogger.logUi("Manual Re-init failed: No radio transport connected")
        }
    }

    fun disconnect() {
        DiagnosticLogger.logSocketBridge("Force disconnect invoked: terminating radio transport")
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        _bridgeState.value = BridgeState.DISCONNECTED
        _activeTransportType.value = TransportType.NONE
        activeTransport = null
        _activeTransportFlow.value = null
        usbTransport.disconnect()
        bleTransport.disconnect()
        logToRns("Radio disconnected. Bridge reset to DISCONNECTED.")
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(detachReceiver)
        } catch (e: Exception) {}
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (e: Exception) {}

        disconnect()
        scope.cancel()
    }
}

package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredBleNode(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isRNodeMatch: Boolean = true
)

class BleRadioTransport(
    private val context: Context,
    private val onLog: ((String) -> Unit)? = null
) : RadioTransport {

    companion object {
        private const val TAG = "BleRadioTransport"

        // Nordic UART Service (NUS)
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write to RNode
        val NUS_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Notify from RNode
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var negotiatedMtu = 23
    private var currentTargetDevice: BluetoothDevice? = null
    private var retryCount = 0
    private var isRetrying = false

    var connectedDeviceName: String? = null
        private set
    var connectedMacAddress: String? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private val frameBuffer = KissFrameBuffer { completeFrame ->
        _incomingBytes.tryEmit(completeFrame)
    }

    // Scanner state
    private val _discoveredNodes = MutableStateFlow<List<DiscoveredBleNode>>(emptyList())
    val discoveredNodes: StateFlow<List<DiscoveredBleNode>> = _discoveredNodes.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Hardware state confirmation for SX1262 RX Mode [0x06, 0x01]
    private val _rxModeConfirmed = MutableStateFlow(false)
    val rxModeConfirmed: StateFlow<Boolean> = _rxModeConfirmed.asStateFlow()

    private val _rxModeRefused = MutableStateFlow(false)
    val rxModeRefused: StateFlow<Boolean> = _rxModeRefused.asStateFlow()

    private val _isRadioReady = MutableStateFlow(false)
    override val isRadioReady: StateFlow<Boolean> = _isRadioReady.asStateFlow()

    // Guard ensuring CMD_RADIO_STATE is sent strictly ONCE per GATT connection
    private val radioStateConfigured = AtomicBoolean(false)

    // Atomic guard on BLE connection lifecycle to prevent duplicate auto-reconnect triggers
    private val isConnectingGuard = AtomicBoolean(false)

    private var scanStopJob: Job? = null

    // BroadcastReceiver for Bluetooth bonding state changes
    private var isBondReceiverRegistered = false
    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            if (device.address != currentTargetDevice?.address) return

            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            log("BLE Device ${device.address} bond state changed: $prevBondState -> $bondState")

            when (bondState) {
                BluetoothDevice.BOND_BONDING -> {
                    log("BLE: Pairing in progress with ${device.address}...")
                }
                BluetoothDevice.BOND_BONDED -> {
                    log("BLE: Device successfully bonded/paired. Initiating service discovery...")
                    val gatt = bluetoothGatt
                    if (gatt != null) {
                        scope.launch {
                            delay(200)
                            gatt.discoverServices()
                        }
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    if (prevBondState == BluetoothDevice.BOND_BONDING) {
                        log("BLE: Pairing was cancelled or failed.")
                        _isConnecting.value = false
                        disconnect()
                    }
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (isBondReceiverRegistered) return
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            ContextCompat.registerReceiver(
                context,
                bondStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isBondReceiverRegistered = true
        } catch (e: Exception) {
            log("Error registering bond receiver: ${e.message}")
        }
    }

    private fun unregisterBondReceiver() {
        if (!isBondReceiverRegistered) return
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (e: Exception) {
            // ignore
        } finally {
            isBondReceiverRegistered = false
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        DiagnosticLogger.log("BLE", msg)
        onLog?.invoke(msg)
    }

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!isConnectingGuard.compareAndSet(false, true)) {
            Log.w("BleTransport", "Connection already in progress. Ignoring duplicate request.")
            DiagnosticLogger.logBle("Connection already in progress. Ignoring duplicate request.")
            return
        }
        try {
            if (!isBluetoothEnabled) {
                log("Bluetooth is disabled. Please enable Bluetooth.")
                return
            }

            retryCount = 0
            currentTargetDevice = device
            doConnectGatt(device)
        } finally {
            isConnectingGuard.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(addressOrDevice: Any) {
        val device: BluetoothDevice? = when (addressOrDevice) {
            is BluetoothDevice -> addressOrDevice
            is String -> {
                try {
                    bluetoothAdapter?.getRemoteDevice(addressOrDevice)
                } catch (e: Exception) {
                    log("Invalid MAC address: $addressOrDevice")
                    null
                }
            }
            else -> null
        }

        if (device == null) {
            log("BLE Connect failed: No valid device or MAC address provided")
            return
        }

        connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun doConnectGatt(device: BluetoothDevice) {
        cleanupGatt()
        registerBondReceiver()

        _isConnecting.value = true
        _isConnected.value = false

        connectedMacAddress = device.address
        connectedDeviceName = try { device.name ?: "Heltec RNode" } catch (e: Exception) { "Heltec RNode" }
        log("Connecting to BLE node: $connectedDeviceName (${device.address})...")

        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            log("GATT Connect exception: ${e.message}")
            _isConnecting.value = false
            disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerRetry(reason: String) {
        val device = currentTargetDevice
        if (device != null && retryCount < 1 && !isRetrying) {
            retryCount++
            isRetrying = true
            log("BLE connection error ($reason). Retrying in 500ms (attempt $retryCount of 1)...")
            cleanupGatt()
            _isConnecting.value = true
            _isConnected.value = false
            scope.launch {
                delay(500)
                isRetrying = false
                doConnectGatt(device)
            }
        } else {
            log("BLE connection failed ($reason). No more retries.")
            _isConnecting.value = false
            _isConnected.value = false
            disconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("BLE onConnectionStateChange status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT connection error: status=$status (133=GATT_ERROR)")
                _isConnected.value = false
                _isConnecting.value = false
                if (status == 133) {
                    log("GATT status 133 (reboot/stale link) encountered. Closing GATT instance and waiting 1000ms...")
                    cleanupGatt()
                    scope.launch {
                        delay(1000)
                        _isConnecting.value = false
                        _isConnected.value = false
                        log("GATT status 133 recovery complete. Ready for manual scan.")
                    }
                    return
                }
                triggerRetry("status=$status")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("BLE Low-level RF link connected. Negotiating MTU (247)...")
                _isConnecting.value = true
                // CRITICAL: Do NOT mark _isConnected = true here! The GATT link is not yet configured.

                // If currently bonding, wait for BOND_BONDED before proceeding
                val bondState = gatt.device.bondState
                if (bondState == BluetoothDevice.BOND_BONDING) {
                    log("BLE device is currently bonding. Waiting for pairing to finish before MTU/service discovery.")
                    return
                }

                val requested = gatt.requestMtu(247)
                if (!requested) {
                    log("requestMtu failed to initiate, starting service discovery directly")
                    proceedToDiscovery(gatt)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("BLE Disconnected from ${gatt.device.address}")
                val wasConnected = _isConnected.value
                _isConnected.value = false
                _isConnecting.value = false
                if (!wasConnected && retryCount < 1 && !isRetrying) {
                    triggerRetry("disconnected during handshake")
                } else {
                    disconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("BLE onMtuChanged: mtu=$mtu, status=$status")
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            proceedToDiscovery(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun proceedToDiscovery(gatt: BluetoothGatt) {
            val bondState = gatt.device.bondState
            if (bondState == BluetoothDevice.BOND_BONDING) {
                log("BLE device is bonding. Waiting for BOND_BONDED before discoverServices().")
                return
            }
            log("Discovering GATT services on ${gatt.device.address}...")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("BLE onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed with status $status")
                triggerRetry("service discovery status=$status")
                return
            }

            val nusService = gatt.getService(NUS_SERVICE_UUID)
            if (nusService == null) {
                log("Nordic UART Service (NUS) not found on device!")
                for (s in gatt.services) {
                    log("Discovered service: ${s.uuid}")
                }
                triggerRetry("NUS service not found")
                return
            }

            rxCharacteristic = nusService.getCharacteristic(NUS_RX_UUID)
            txCharacteristic = nusService.getCharacteristic(NUS_TX_UUID)

            val txChar = txCharacteristic
            val rxChar = rxCharacteristic
            if (rxChar == null || txChar == null) {
                log("Missing NUS RX or TX characteristic!")
                triggerRetry("missing NUS RX/TX characteristic")
                return
            }

            log("Enabling local notifications on NUS TX characteristic (${txChar.uuid})...")
            val setNotifySuccess = gatt.setCharacteristicNotification(txChar, true)
            if (!setNotifySuccess) {
                log("Failed to setCharacteristicNotification locally")
            }

            // Write CCCD (0x2902) with ENABLE_NOTIFICATION_VALUE to activate firmware stream
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                log("Writing CCCD (0x2902) ENABLE_NOTIFICATION_VALUE to inform Heltec RNode firmware...")
                val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                if (!writeSuccess) {
                    log("Failed to initiate CCCD descriptor write!")
                    triggerRetry("CCCD write initiation failed")
                }
            } else {
                log("CCCD descriptor not found on TX characteristic. Fallback to ready.")
                _isConnected.value = true
                _isConnecting.value = false
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("BLE onDescriptorWrite: ${descriptor.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.characteristic.uuid == NUS_TX_UUID) {
                log("BLE NUS CCCD write verified. Heltec RNode notification channel active. Waking SX1262 radio...")
                DiagnosticLogger.logBle("NUS CCCD write verified. Starting canonical RNode wakeup sequence...")
                scope.launch {
                    try {
                        delay(50)
                        _rxModeConfirmed.value = false
                        _rxModeRefused.value = false
                        _isRadioReady.value = false

                        wakeAndConfigureRNode(force = true)

                        // Wait up to 1500 ms for RNode [0x06, 0x01] or [0x06, 0x00]
                        var waitMs = 0L
                        while (waitMs < 1500L && !_rxModeConfirmed.value && !_rxModeRefused.value) {
                            delay(50)
                            waitMs += 50
                        }

                        // If refused (0x00), retry config once
                        if (_rxModeRefused.value) {
                            log("[BLE] RNode refused RX mode (0x06 0x00). Retrying config...")
                            DiagnosticLogger.logBle("[BLE] RNode refused RX mode (0x06 0x00). Retrying config...")
                            _rxModeRefused.value = false
                            _rxModeConfirmed.value = false
                            delay(100)
                            wakeAndConfigureRNode(force = true)

                            waitMs = 0L
                            while (waitMs < 1500L && !_rxModeConfirmed.value && !_rxModeRefused.value) {
                                delay(50)
                                waitMs += 50
                            }
                        }

                        if (_rxModeConfirmed.value) {
                            _isRadioReady.value = true
                            _isConnected.value = true
                            _isConnecting.value = false
                            log("BLE Link READY! SX1262 LoRa radio awake in RX mode at 915.0 MHz.")
                            DiagnosticLogger.logBle("Hardware ACK confirmed: SX1262 locked in RX mode. BLE link READY.")
                        } else {
                            // Hardware response was a timeout or 0x00 refused. Never falsely report ready.
                            _isRadioReady.value = false
                            _isConnected.value = false
                            _isConnecting.value = false
                            if (_rxModeRefused.value) {
                                log("[BLE] RNode refused RX mode (0x06 0x00). Configuration invalid. Radio link NOT ready.")
                                DiagnosticLogger.logBle("[BLE] RNode refused RX mode (0x06 0x00). Configuration invalid. Radio link NOT ready.")
                            } else {
                                log("Hardware ACK timeout (1500ms). RNode did not confirm RX mode. Radio link NOT ready.")
                                DiagnosticLogger.logBle("Hardware ACK timeout (1500ms). RNode did not confirm RX mode. Radio link NOT ready.")
                            }
                        }
                    } catch (e: Exception) {
                        log("Error during wakeAndConfigureRNode: ${e.message}")
                        _isRadioReady.value = false
                        _isConnected.value = false
                        _isConnecting.value = false
                    }
                }
            } else {
                log("BLE Descriptor write failed: status=$status")
                triggerRetry("CCCD write failed status=$status")
            }
        }

        private fun checkHardwareAck(data: ByteArray) {
            // RNode canonical ACK format for CMD_RADIO_STATE (0x06): [0xC0, 0x06, 0x01, 0xC0] or contains [0x06, 0x01] / [0x06, 0x00]
            for (i in 0 until data.size - 1) {
                if (data[i] == 0x06.toByte() && data[i + 1] == 0x01.toByte()) {
                    _rxModeConfirmed.value = true
                    _rxModeRefused.value = false
                    _isRadioReady.value = true
                    log("RNode Hardware ACK received: SX1262 PLL locked in RX mode [0x06, 0x01]!")
                    DiagnosticLogger.logBle("RNode Hardware ACK verified: SX1262 PLL locked in RX mode [0x06, 0x01]")
                    break
                } else if (data[i] == 0x06.toByte() && data[i + 1] == 0x00.toByte()) {
                    _rxModeConfirmed.value = false
                    _rxModeRefused.value = true
                    _isRadioReady.value = false
                    log("[BLE] RNode refused RX mode (0x06 0x00).")
                    DiagnosticLogger.logBle("[BLE] RNode refused RX mode (0x06 0x00).")
                    break
                }
            }
            // Check for detection probe (0x0B) response string
            try {
                val str = String(data, Charsets.US_ASCII)
                if (str.contains("RNode") || str.contains("ESP32")) {
                    val cleaned = str.replace(Regex("[^\\x20-\\x7E]"), " ").trim()
                    if (cleaned.isNotEmpty()) {
                        log("RNode Hardware ID: $cleaned")
                        DiagnosticLogger.logBle("RNode ID Telemetry: $cleaned")
                    }
                }
            } catch (e: Exception) {}
        }

        // Android < 13
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NUS_TX_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                if (value != null && value.isNotEmpty()) {
                    Log.d("BleRadioTransport", "[BLE RX]: ${value.size} bytes")
                    DiagnosticLogger.logBleRawRx(value)
                    checkHardwareAck(value)
                    frameBuffer.feed(value)
                }
            }
        }

        // Android 13+ (API 33)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX_UUID && value.isNotEmpty()) {
                Log.d("BleRadioTransport", "[BLE RX]: ${value.size} bytes")
                DiagnosticLogger.logBleRawRx(value)
                checkHardwareAck(value)
                frameBuffer.feed(value) // Reassembles FEND-delimited KISS frames before emitting
            }
        }
    }

    suspend fun wakeAndConfigureRNode(force: Boolean = false) {
        if (!force && !radioStateConfigured.compareAndSet(false, true)) {
            log("RNode configuration (CMD_RADIO_STATE) already sent once for this connection. Skipping redundant re-issue.")
            DiagnosticLogger.logBle("RNode config already sent. Skipping duplicate radio state reset.")
            return
        }
        if (force) {
            radioStateConfigured.set(true)
        }

        log("Sending canonical RNode KISS hardware wakeup sequence over BLE (once on connection)...")
        DiagnosticLogger.logBle("Sending canonical RNode KISS frames: 0x01 freq, 0x02 bw, 0x03 txpower, 0x04 sf, 0x05 cr, 0x06 rx, 0x0B detect...")

        // Canonical RNode command table:
        // 1. Radio Frequency: 915.0 MHz -> 915000000 Hz = 0x3689CAC0 (CMD_FREQUENCY = 0x01)
        sendRawKissFrame(byteArrayOf(0x01, 0x36.toByte(), 0x89.toByte(), 0xCA.toByte(), 0xC0.toByte()))
        delay(35)

        // 2. Bandwidth: 125 kHz -> 125000 Hz = 0x0001E848 (CMD_BANDWIDTH = 0x02)
        sendRawKissFrame(byteArrayOf(0x02, 0x00.toByte(), 0x01.toByte(), 0xE8.toByte(), 0x48.toByte()))
        delay(35)

        // 3. TX Power: 7 dBm (CMD_TXPOWER = 0x03)
        sendRawKissFrame(byteArrayOf(0x03, 0x07.toByte()))
        delay(35)

        // 4. Spreading Factor: SF8 (CMD_SF = 0x04)
        sendRawKissFrame(byteArrayOf(0x04, 0x08.toByte()))
        delay(35)

        // 5. Coding Rate: 4/5 (CMD_CR = 0x05, 5 for 4/5)
        sendRawKissFrame(byteArrayOf(0x05, 0x05.toByte()))
        delay(35)

        // 6. Set Radio State: Promiscuous RX (0x01) - wakes SX1262 from standby into RX mode (CMD_RADIO_STATE = 0x06)
        sendRawKissFrame(byteArrayOf(0x06, 0x01.toByte()))
        delay(35)

        // 7. Detection Probe: CMD_DETECT = 0x0B
        sendRawKissFrame(byteArrayOf(0x0B.toByte()))
        delay(35)
    }

    fun sendRawKissFrame(payload: ByteArray): Boolean {
        val escaped = ByteArrayOutputStream().apply {
            write(0xC0) // Leading FEND
            for (b in payload) {
                when (b) {
                    0xC0.toByte() -> {
                        write(0xDB)
                        write(0xDC)
                    }
                    0xDB.toByte() -> {
                        write(0xDB)
                        write(0xDD)
                    }
                    else -> write(b.toInt() and 0xFF)
                }
            }
            write(0xC0) // Trailing FEND
        }.toByteArray()

        return sendBytes(escaped)
    }

    override fun initRadioState(): Boolean {
        scope.launch { wakeAndConfigureRNode() }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun sendBytes(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val rxChar = rxCharacteristic ?: return false
        if (!_isConnected.value && !_isConnecting.value) return false
        if (data.isEmpty()) return false

        // Python Reticulum has kiss_framing = True and emits complete KISS frames.
        // Eliminate double KISS framing: forward directly to hardware stream without extra 0xC0 or re-framing.
        val frame = data

        // Check negotiated MTU (effective ATT payload up to 244 bytes on MTU 247)
        val safeMax = if (negotiatedMtu > 23) (negotiatedMtu - 3).coerceIn(20, 244) else 20
        // CRITICAL: RNode Nordic UART RX characteristic expects WRITE_TYPE_NO_RESPONSE
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        Log.d("BleRadioTransport", "[BLE TX]: ${frame.size} bytes (chunkSize=$safeMax)")
        DiagnosticLogger.logBleRawTx(frame)

        return try {
            if (frame.size <= safeMax) {
                writeChunk(gatt, rxChar, frame, writeType)
            } else {
                var offset = 0
                var allSuccess = true
                while (offset < frame.size) {
                    val end = minOf(offset + safeMax, frame.size)
                    val chunk = frame.copyOfRange(offset, end)
                    if (!writeChunk(gatt, rxChar, chunk, writeType)) {
                        allSuccess = false
                        break
                    }
                    offset = end
                    if (offset < frame.size) {
                        // 10ms delay between chunks to avoid buffer overflow in BLE controller
                        Thread.sleep(10)
                    }
                }
                allSuccess
            }
        } catch (e: Exception) {
            log("BLE sendBytes error: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChunk(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = gatt.writeCharacteristic(char, chunk, writeType)
            code == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = chunk
            char.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupGatt() {
        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {}
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        _isConnected.value = false
        _isConnecting.value = false
        _isRadioReady.value = false
        _rxModeConfirmed.value = false
        _rxModeRefused.value = false
        isConnectingGuard.set(false)
        currentTargetDevice = null
        retryCount = 0
        isRetrying = false
        frameBuffer.reset()
        radioStateConfigured.set(false)
        unregisterBondReceiver()
        cleanupGatt()
    }

    // --- BLE Scanning ---

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val scanRecord = result.scanRecord

            val rawName = try { device.name } catch (e: Exception) { null } ?: scanRecord?.deviceName ?: ""
            val address = device.address ?: return
            val rssi = result.rssi

            val serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString().uppercase() } ?: emptyList()
            val hasNusUuid = serviceUuids.any { it.contains("6E400001") }
            val startsWithRNode = rawName.startsWith("RNode", ignoreCase = true)
            val isRNodeMatch = hasNusUuid || startsWithRNode || rawName.contains("Heltec", ignoreCase = true)

            // Only include devices matching RNode / NUS or named devices with RNode
            if (isRNodeMatch || startsWithRNode || hasNusUuid) {
                val displayName = if (rawName.isNotBlank()) rawName else "RNode (${address.takeLast(5)})"
                val node = DiscoveredBleNode(
                    device = device,
                    name = displayName,
                    address = address,
                    rssi = rssi,
                    isRNodeMatch = true
                )

                val current = _discoveredNodes.value.toMutableList()
                val index = current.indexOfFirst { it.address.equals(address, ignoreCase = true) }
                if (index >= 0) {
                    current[index] = node
                } else {
                    current.add(node)
                }
                // Sort by RSSI descending
                current.sortByDescending { it.rssi }
                _discoveredNodes.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE Scan failed: code $errorCode")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(timeoutMs: Long = 15000L) {
        if (!isBluetoothEnabled) {
            log("Bluetooth not enabled for scan")
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            log("BLE Scanner unavailable")
            return
        }

        _discoveredNodes.value = emptyList()
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Scan broadly and filter in callback to catch devices advertising either NUS UUID or "RNode" name
        try {
            scanner.startScan(null, settings, scanCallback)
            log("BLE Scan started...")
        } catch (e: Exception) {
            log("startScan error: ${e.message}")
            _isScanning.value = false
            return
        }

        scanStopJob?.cancel()
        scanStopJob = scope.launch {
            delay(timeoutMs)
            stopScanning()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!_isScanning.value) return
        _isScanning.value = false
        scanStopJob?.cancel()
        scanStopJob = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            log("BLE Scan stopped.")
        } catch (e: Exception) {
            // ignore
        }
    }
}

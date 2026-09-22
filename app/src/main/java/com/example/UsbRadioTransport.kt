package com.example

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UsbRadioTransport(
    private val context: Context,
    private val onLog: ((String) -> Unit)? = null
) : RadioTransport {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
        private const val TAG = "UsbRadioTransport"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var serialPort: UsbSerialPort? = null
        private set

    private var readJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private val frameBuffer = KissFrameBuffer { completeFrame ->
        _incomingBytes.tryEmit(completeFrame)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    override fun connect(addressOrDevice: Any) {
        scope.launch(Dispatchers.IO) {
            try {
                val targetDevice: UsbDevice? = when (addressOrDevice) {
                    is UsbDevice -> addressOrDevice
                    else -> findFirstUsbDevice()
                }

                if (targetDevice == null) {
                    log("No USB serial device available to connect")
                    return@launch
                }

                val driver = probeDriver(targetDevice)
                if (driver == null) {
                    log("No compatible USB serial driver found for ${targetDevice.deviceName}")
                    return@launch
                }

                if (!usbManager.hasPermission(targetDevice)) {
                    log("Requesting USB permission for ${targetDevice.deviceName}...")
                    val permIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(targetDevice, permIntent)
                    return@launch
                }

                openDriver(driver)
            } catch (e: Exception) {
                log("USB Connect error: ${e.message}")
                disconnect()
            }
        }
    }

    fun hasDeviceAttached(): Boolean {
        return usbManager.deviceList.isNotEmpty()
    }

    private fun findFirstUsbDevice(): UsbDevice? {
        val customTable = ProbeTable().apply {
            addProduct(12346, 4097, CdcAcmSerialDriver::class.java) // ESP32-S3 CDC-ACM
        }
        val prober = UsbSerialProber(customTable)
        val drivers = prober.findAllDrivers(usbManager)
        if (drivers.isNotEmpty()) return drivers[0].device

        val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (defaultDrivers.isNotEmpty()) return defaultDrivers[0].device

        return usbManager.deviceList.values.firstOrNull()
    }

    private fun probeDriver(device: UsbDevice): UsbSerialDriver? {
        val customTable = ProbeTable().apply {
            addProduct(12346, 4097, CdcAcmSerialDriver::class.java)
        }
        val prober = UsbSerialProber(customTable)
        return prober.probeDevice(device) ?: UsbSerialProber.getDefaultProber().probeDevice(device)
    }

    private fun openDriver(driver: UsbSerialDriver) {
        disconnect()

        val connection = usbManager.openDevice(driver.device) ?: run {
            log("Failed to open USB device connection.")
            return
        }

        val port = driver.ports[0]
        port.open(connection)
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.dtr = true
        port.rts = false

        serialPort = port
        _isConnected.value = true
        log("USB Serial port opened: ${driver.device.deviceName} (115200 8N1, DTR=true, RTS=false)")
        DiagnosticLogger.log("USB", "CDC-ACM serial port opened (115200 8N1, DTR=true, RTS=false)")

        startReading(port)
        initRadioState()
    }

    override fun initRadioState(): Boolean {
        return RNodeRadioConfig.sendInitSequence(this) { log(it) }
    }

    private fun startReading(port: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive && _isConnected.value) {
                try {
                    val len = port.read(buffer, 200)
                    if (len > 0) {
                        val chunk = buffer.copyOf(len)
                        frameBuffer.feed(chunk)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("Timeout", ignoreCase = true) != true) {
                        log("USB Read error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun sendBytes(data: ByteArray): Boolean {
        val port = serialPort ?: return false
        if (!_isConnected.value) return false
        if (data.isEmpty()) return false
        // Python Reticulum has kiss_framing = True and emits complete KISS frames.
        // Eliminate double KISS framing: forward directly to the hardware stream without extra 0xC0 or re-framing.
        return try {
            port.write(data, 1000)
            true
        } catch (e: Exception) {
            log("USB Write error: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        _isConnected.value = false
        readJob?.cancel()
        readJob = null
        frameBuffer.reset()
        try {
            serialPort?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        serialPort = null
    }
}

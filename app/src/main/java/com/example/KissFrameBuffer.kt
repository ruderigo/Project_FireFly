package com.example

import java.io.ByteArrayOutputStream

/**
 * Handles buffering, frame de-fragmentation, and escaping/un-escaping for KISS protocol frames.
 * Delimited by KISS_FEND (0xC0).
 */
class KissFrameBuffer(
    private val maxFrameSize: Int = 2048,
    private val onFrameReceived: (ByteArray) -> Unit
) {
    companion object {
        const val KISS_FEND: Byte = 0xC0.toByte()
        const val KISS_FESC: Byte = 0xDB.toByte()
        const val KISS_TFEND: Byte = 0xDC.toByte()
        const val KISS_TFESC: Byte = 0xDD.toByte()

        const val CMD_DATA: Byte = 0x00.toByte()
        const val CMD_FREQUENCY: Byte = 0x01.toByte()
        const val CMD_BANDWIDTH: Byte = 0x02.toByte()
        const val CMD_TXPOWER: Byte = 0x03.toByte()
        const val CMD_SF: Byte = 0x04.toByte()
        const val CMD_CR: Byte = 0x05.toByte()
        const val CMD_RADIO_STATE: Byte = 0x06.toByte()
        const val CMD_DETECT: Byte = 0x0B.toByte()

        fun escapeKiss(data: ByteArray): ByteArray {
            val out = ArrayList<Byte>(data.size + 16)
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

        fun wrapFrame(command: Byte, unescapedPayload: ByteArray = byteArrayOf()): ByteArray {
            val escaped = escapeKiss(unescapedPayload)
            return byteArrayOf(KISS_FEND, command) + escaped + byteArrayOf(KISS_FEND)
        }

        fun ensureFramed(data: ByteArray): ByteArray {
            if (data.size >= 2 && data.first() == KISS_FEND && data.last() == KISS_FEND) {
                return data
            }
            return byteArrayOf(KISS_FEND) + data + byteArrayOf(KISS_FEND)
        }
    }

    private var inFrame = false
    private var isEscaped = false
    private var hasCommand = false
    private var currentCommand: Byte = 0
    private val payloadBuffer = ByteArrayOutputStream()

    @Synchronized
    fun feed(chunk: ByteArray) {
        for (b in chunk) {
            if (b == KISS_FEND) {
                if (inFrame && hasCommand) {
                    // Closing FEND received - valid frame complete
                    val unescaped = payloadBuffer.toByteArray()
                    // Discard empty frames unless it's a non-data command
                    if (unescaped.isNotEmpty() || currentCommand != CMD_DATA) {
                        val completeFrame = wrapFrame(currentCommand, unescaped)
                        onFrameReceived(completeFrame)
                    }
                    // Reset for next frame
                    inFrame = false
                    isEscaped = false
                    hasCommand = false
                    payloadBuffer.reset()
                } else {
                    // Opening FEND received
                    inFrame = true
                    isEscaped = false
                    hasCommand = false
                    payloadBuffer.reset()
                }
            } else if (inFrame) {
                if (!hasCommand) {
                    currentCommand = (b.toInt() and 0x0F).toByte()
                    hasCommand = true
                } else {
                    if (payloadBuffer.size() >= maxFrameSize) {
                        // Frame too long, discard corrupted stream
                        inFrame = false
                        isEscaped = false
                        hasCommand = false
                        payloadBuffer.reset()
                    } else if (isEscaped) {
                        when (b) {
                            KISS_TFEND -> payloadBuffer.write(KISS_FEND.toInt())
                            KISS_TFESC -> payloadBuffer.write(KISS_FESC.toInt())
                            else -> payloadBuffer.write(b.toInt())
                        }
                        isEscaped = false
                    } else if (b == KISS_FESC) {
                        isEscaped = true
                    } else {
                        payloadBuffer.write(b.toInt())
                    }
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        inFrame = false
        isEscaped = false
        hasCommand = false
        currentCommand = 0
        payloadBuffer.reset()
    }
}

object RNodeRadioConfig {
    const val FREQ_915_MHZ = 915000000 // 915.0 MHz -> 0x3689CAC0
    const val BW_125_KHZ = 125000       // 125 kHz -> 0x0001E848
    const val TX_POWER_7_DBM = 7        // 7 dBm -> 0x07
    const val SF_8 = 8                  // SF8 -> 0x08
    const val CR_5 = 5                  // 4/5 (CR5) -> 0x05
    const val RADIO_STATE_RX = 1        // RX Mode / Promiscuous = 0x01

    fun buildInitFrames(): List<ByteArray> {
        val list = mutableListOf<ByteArray>()

        // 1. Flush preambles
        list.add(byteArrayOf(KissFrameBuffer.KISS_FEND, KissFrameBuffer.KISS_FEND))

        // 2. Frequency: 915.0 MHz (Command 0x01: CMD_FREQUENCY) -> 0x3689CAC0
        val freqBytes = byteArrayOf(
            ((FREQ_915_MHZ shr 24) and 0xFF).toByte(),
            ((FREQ_915_MHZ shr 16) and 0xFF).toByte(),
            ((FREQ_915_MHZ shr 8) and 0xFF).toByte(),
            (FREQ_915_MHZ and 0xFF).toByte()
        )
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_FREQUENCY, freqBytes))

        // 3. Bandwidth: 125 kHz (Command 0x02: CMD_BANDWIDTH) -> 0x0001E848
        val bwBytes = byteArrayOf(
            ((BW_125_KHZ shr 24) and 0xFF).toByte(),
            ((BW_125_KHZ shr 16) and 0xFF).toByte(),
            ((BW_125_KHZ shr 8) and 0xFF).toByte(),
            (BW_125_KHZ and 0xFF).toByte()
        )
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_BANDWIDTH, bwBytes))

        // 4. TX Power: 7 dBm (Command 0x03: CMD_TXPOWER) -> 0x07
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_TXPOWER, byteArrayOf(TX_POWER_7_DBM.toByte())))

        // 5. Spreading Factor: SF8 (Command 0x04: CMD_SF) -> 0x08
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_SF, byteArrayOf(SF_8.toByte())))

        // 6. Coding Rate: 4/5 (Command 0x05: CMD_CR) -> 0x05
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_CR, byteArrayOf(CR_5.toByte())))

        // 7. Radio State: RX Mode (Command 0x06: CMD_RADIO_STATE / Promiscuous = 0x01)
        // CRITICAL: Brings SX1262 LoRa transceiver out of standby into RX mode
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_RADIO_STATE, byteArrayOf(RADIO_STATE_RX.toByte())))

        // 8. Detection Probe (Command 0x0B: CMD_DETECT) to request RNode telemetry
        list.add(KissFrameBuffer.wrapFrame(KissFrameBuffer.CMD_DETECT))

        return list
    }

    fun sendInitSequence(transport: RadioTransport, log: ((String) -> Unit)? = null): Boolean {
        log?.invoke("RNode: Configuring radio with canonical RNode layout (915.0 MHz, 125 kHz, TX 7 dBm, SF8, CR 4/5, RX Mode)...")
        val frames = buildInitFrames()
        for (f in frames) {
            transport.sendBytes(f)
            try {
                Thread.sleep(35)
            } catch (e: Exception) {}
        }
        log?.invoke("RNode: Radio initialized in RX mode on 915.0 MHz")
        return true
    }
}

package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RadioTelemetry(
    val batteryMilliVolts: Int = 0,
    val batteryPercent: Int = 0,
    val channelUtilization: Float = 0.0f,
    val radioTxPackets: Long = 0L,
    val radioRxPackets: Long = 0L,
    val lastUpdateTimestamp: Long = 0L
)

object RadioTelemetryState {
    private val _telemetry = MutableStateFlow(RadioTelemetry())
    val telemetry: StateFlow<RadioTelemetry> = _telemetry.asStateFlow()

    fun updateFromKissTelemetry(cmd: Byte, payload: ByteArray) {
        val cmdInt = cmd.toInt() and 0xFF
        val current = _telemetry.value
        when (cmdInt) {
            0x25 -> {
                // Radio packet / byte counters
                var tx = current.radioTxPackets
                var rx = current.radioRxPackets
                if (payload.size >= 4) {
                    tx = (((payload[0].toLong() and 0xFF) shl 8) or (payload[1].toLong() and 0xFF))
                    rx = (((payload[2].toLong() and 0xFF) shl 8) or (payload[3].toLong() and 0xFF))
                } else if (payload.size >= 2) {
                    tx = (((payload[0].toLong() and 0xFF) shl 8) or (payload[1].toLong() and 0xFF))
                }
                _telemetry.value = current.copy(
                    radioTxPackets = tx,
                    radioRxPackets = rx,
                    lastUpdateTimestamp = System.currentTimeMillis()
                )
            }
            0x27 -> {
                // Battery voltage and channel utilization
                var mv = current.batteryMilliVolts
                var pct = current.batteryPercent
                var util = current.channelUtilization
                if (payload.size >= 2) {
                    val rawMv = (((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
                    if (rawMv in 2000..5000) {
                        mv = rawMv
                        pct = maxOf(0, minOf(100, ((rawMv - 3300) * 100) / 900))
                    } else if (rawMv in 0..100) {
                        pct = rawMv
                    }
                }
                if (payload.size >= 3) {
                    val rawUtil = payload[2].toInt() and 0xFF
                    util = rawUtil.toFloat()
                }
                _telemetry.value = current.copy(
                    batteryMilliVolts = mv,
                    batteryPercent = pct,
                    channelUtilization = util,
                    lastUpdateTimestamp = System.currentTimeMillis()
                )
            }
            0x29 -> {
                // Firmware / stats ping
                _telemetry.value = current.copy(
                    lastUpdateTimestamp = System.currentTimeMillis()
                )
            }
        }
    }
}

package com.example

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RadioTransport {
    fun connect(addressOrDevice: Any)
    fun disconnect()
    fun sendBytes(data: ByteArray): Boolean
    fun initRadioState(): Boolean = true
    val isConnected: StateFlow<Boolean>
    val incomingBytes: SharedFlow<ByteArray>
    val isRadioReady: StateFlow<Boolean> get() = isConnected
}

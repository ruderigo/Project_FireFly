package com.example

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ChatViewModel provides debounced, thread-safe message dispatching
 * to prevent duplicate LXMF outbound packets and manage send state.
 */
class ChatViewModel : ViewModel() {
    private val TAG = "ChatViewModel"

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val sendMutex = Mutex()

    /**
     * Send a direct message to a destination hash.
     * Enforces debouncing via sendMutex.tryLock() and a 1000ms cooldown
     * so repeat taps or concurrent triggers within the flight/cooldown window are dropped
     * and only a single outbound transmission is dispatched.
     */
    fun sendMessage(
        destHash: String,
        content: String,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        var targetDest = destHash.trim().lowercase()
        val trimmedContent = content.trim()
        if (targetDest.isEmpty() || trimmedContent.isEmpty()) {
            onResult?.invoke(false)
            return
        }

        val isStump = targetDest.equals(com.example.data.STUMP_DM_VIRTUAL_HASH, ignoreCase = true) ||
            com.example.data.DirectMessageRepository.isStumpHash(targetDest)

        if (isStump) {
            val liveHash = MeshService.resolveLiveStumpHash()
            if (!liveHash.isNullOrBlank()) {
                targetDest = liveHash
            }
        } else {
            // Target is a standard 1-on-1 peer: pure raw 16-byte destination hex
            targetDest = destHash.trim().lowercase()
        }

        viewModelScope.launch {
            if (!sendMutex.tryLock()) {
                Log.d(TAG, "Send dropped: Operation already in flight")
                return@launch
            }
            _isSending.value = true
            try {
                Log.d(TAG, "Queuing message to Chaquopy: dest=$targetDest len=${trimmedContent.length}")
                val ok = withContext(Dispatchers.IO) {
                    MeshService.sendDirectMessage(targetDest, trimmedContent)
                }
                withContext(Dispatchers.Main) {
                    onResult?.invoke(ok)
                }
                delay(1000) // Cooldown period
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendMessage: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false)
                }
            } finally {
                _isSending.value = false
                sendMutex.unlock()
            }
        }
    }
}

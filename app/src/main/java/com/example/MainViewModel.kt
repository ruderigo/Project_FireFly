package com.example

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
    }

    val usbBridge: UsbRNodeBridge = UsbRNodeBridge.getInstance(application)

    private val _isBackendReady = MutableStateFlow(false)
    val isBackendReady: StateFlow<Boolean> = _isBackendReady.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    init {
        MeshService.initContext(application)
        viewModelScope.launch {
            MeshService.isBackendReady.collect { ready ->
                _isBackendReady.value = ready
            }
        }
    }

    /**
     * Delegates Python/Reticulum runtime check to ReticulumBridge singleton.
     */
    fun initPythonBackend() {
        if (_isBackendReady.value || _isInitializing.value) return
        _isInitializing.value = true

        try {
            val app = getApplication<Application>()
            ReticulumBridge.initialize(app)
            _isBackendReady.value = true
            MeshService.markBackendReady()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Python backend: ${e.message}", e)
        } finally {
            _isInitializing.value = false
        }
    }
}

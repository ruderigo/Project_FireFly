package com.example

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.FireflyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var usbBridge: UsbRNodeBridge
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        usbBridge = mainViewModel.usbBridge

        // Render Compose UI immediately to unblock the splash screen
        setContent {
            FireflyTheme {
                MainAppScreen(viewModel = mainViewModel)
            }
        }

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbBridge.startServerAndConnectUsb()
        } else if (!usbBridge.isUsbDevicePluggedIn()) {
            usbBridge.autoReconnectBleIfAvailable()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbBridge.startServerAndConnectUsb()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        usbBridge.cleanup()
    }
}

package com.example

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.ui.theme.MyApplicationTheme
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : ComponentActivity() {
    private lateinit var usbBridge: UsbRNodeBridge
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this.applicationContext))
        }
        
        usbBridge = UsbRNodeBridge(this)
        
        setContent {
            MyApplicationTheme {
                MainScreen(
                    usbBridge = usbBridge,
                    context = this
                )
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

package com.example

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class FireflyApp : Application() {
    companion object {
        private val isStarted = AtomicBoolean(false)
    }

    override fun onCreate() {
        super.onCreate()
        if (isStarted.compareAndSet(false, true)) {
            // Start Python RNS runtime once on process creation
            CoroutineScope(Dispatchers.IO).launch {
                ReticulumBridge.initialize(applicationContext)
            }
        }
    }
}

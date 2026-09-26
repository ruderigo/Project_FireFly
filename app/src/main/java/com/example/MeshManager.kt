package com.example

import android.content.Context
import com.chaquo.python.Python

/**
 * High-level manager for mesh networking operations such as announcing presence.
 */
class MeshManager(private val context: Context) {
    fun sendAnnounce(): Boolean {
        return MeshService.sendAnnounce()
    }
}

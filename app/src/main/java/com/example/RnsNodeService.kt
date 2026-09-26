package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class RnsNodeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "rns_node_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.action.START_RNS_SERVICE"
        const val ACTION_STOP = "com.example.action.STOP_RNS_SERVICE"

        fun start(context: Context) {
            try {
                val intent = Intent(context, RnsNodeService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, RnsNodeService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        RadioBridgeServer.startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RadioBridgeServer.startServer()
        if (intent?.action == ACTION_STOP) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } catch (e: Throwable) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } catch (e2: Throwable) {
                        e2.printStackTrace()
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "RnsNodeService::KeepAliveWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24 hours max
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "RNS Node Keepalive",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Reticulum Network Stack and USB LoRa bridge active in background"
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reticulum Node Running")
            .setContentText("SX1262 LoRa USB Bridge & Mesh Active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }
}

package com.example.translationapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ScreenCaptureService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            sendBroadcast(Intent("com.example.translationapplication.STOP_CAPTURE"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val channelId = "screen_capture_channel"
        val channel = NotificationChannel(
            channelId,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop Translation",
            stopPendingIntent
        ).build()

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Translation Running")
            .setContentText("Capturing screen for translation")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
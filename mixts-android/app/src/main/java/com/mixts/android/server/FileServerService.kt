package com.mixts.android.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mixts.android.MixtsApp
import com.mixts.android.R
import com.mixts.android.ui.MainActivity

class FileServerService : Service() {

    companion object {
        const val CHANNEL_ID = "mixts_server_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.mixts.android.action.START_SERVER"
        const val ACTION_STOP = "com.mixts.android.action.STOP_SERVER"
        const val EXTRA_PORT = "port"
        const val EXTRA_URL = "url"

        fun createStartIntent(context: Context, port: Int, url: String): Intent {
            return Intent(context, FileServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_URL, url)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, FileServerService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                startForeground(NOTIFICATION_ID, createNotification(port, url))
            }
            ACTION_STOP -> {
                // 停止服务器
                MixtsApp.stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service 销毁时停止服务器
        MixtsApp.stopServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件传输服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示文件传输服务状态"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int, url: String): Notification {
        // 使用 FLAG_UPDATE_CURRENT 确保 Intent 是最新的
        // 使用 FLAG_CANCEL_CURRENT 确保每次都创建新的
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                // 关键：防止多个 Activity 实例
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            createStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mixts 服务运行中")
            .setContentText("访问地址: $url")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

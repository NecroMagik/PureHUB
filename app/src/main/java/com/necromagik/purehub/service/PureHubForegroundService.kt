package com.necromagik.purehub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.necromagik.purehub.IPureHubService

private const val TAG = "PureHUB-Diag"

class PureHubForegroundService : Service() {

    private var connection: UserServiceConnection? = null
    private var userService: IPureHubService? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[ForegroundService] onCreate вызван")
        startForegroundServiceNotification()
        bindToShizukuService()
    }

    private fun bindToShizukuService() {
        Log.d(TAG, "[ForegroundService] Запуск привязки к Shizuku UserService...")
        connection = UserServiceConnection(
            onConnected = { service ->
                Log.i(TAG, "[ForegroundService] Успешно подключен к Shizuku IPC")
                userService = service
                try {
                    service.startVolumeListener()
                    Log.i(TAG, "[ForegroundService] startVolumeListener() выполнен")
                } catch (e: Exception) {
                    Log.e(TAG, "[ForegroundService] Ошибка вызова startVolumeListener", e)
                }
            },
            onDisconnected = {
                Log.w(TAG, "[ForegroundService] Связь с Shizuku IPC разорвана")
                userService = null
            }
        )
        connection?.bind()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "purehub_service_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PureHUB Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PureHUB активен")
            .setContentText("Управление треками кнопками громкости включено")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
        Log.d(TAG, "[ForegroundService] Foreground Notification запущена")
    }

    override fun onDestroy() {
        Log.i(TAG, "[ForegroundService] onDestroy вызван, остановка")
        try {
            userService?.stopVolumeListener()
        } catch (e: Exception) {
            Log.e(TAG, "[ForegroundService] Ошибка stopVolumeListener", e)
        }
        connection?.unbind()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
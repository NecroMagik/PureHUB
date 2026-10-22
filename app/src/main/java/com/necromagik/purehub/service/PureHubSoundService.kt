package com.necromagik.purehub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class PureHubSoundService : Service() {

    private var soundPool: SoundPool? = null
    private var lockSoundId: Int = 0
    private var unlockSoundId: Int = 0

    private val screenEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (lockSoundId != 0) {
                        soundPool?.play(lockSoundId, 1f, 1f, 1, 0, 1f)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (unlockSoundId != 0) {
                        soundPool?.play(unlockSoundId, 1f, 1f, 1, 0, 1f)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initSoundPool()
        loadSounds()

        // Отключаем встроенный системный звук блокировки, чтобы не двоился
        SoundController.setSystemLockSoundsEnabled(false)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenEventsReceiver, filter)

        startForeground(1001, createNotification())
    }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    private fun loadSounds() {
        val lockFile = File(filesDir, "lock.ogg")
        if (lockFile.exists()) {
            lockSoundId = soundPool?.load(lockFile.absolutePath, 1) ?: 0
        }

        val unlockFile = File(filesDir, "unlock.ogg")
        if (unlockFile.exists()) {
            unlockSoundId = soundPool?.load(unlockFile.absolutePath, 1) ?: 0
        }
    }

    private fun createNotification(): Notification {
        val channelId = "purehub_sounds"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Служба системных звуков PureHUB",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("PureHUB Sound Engine")
            .setContentText("Кастомные системные звуки активны")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenEventsReceiver)
        soundPool?.release()
        soundPool = null
        // Восстанавливаем системный звук при остановке службы
        SoundController.setSystemLockSoundsEnabled(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
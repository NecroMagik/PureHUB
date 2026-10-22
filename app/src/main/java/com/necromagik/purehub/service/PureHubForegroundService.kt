package com.necromagik.purehub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.necromagik.purehub.IPureHubService
import com.necromagik.purehub.R
import rikka.shizuku.Shizuku

class PureHubForegroundService : Service() {

    companion object {
        private const val TAG = "PureHUB-FGService"
        const val CHANNEL_ID = "purehub_service_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_TOGGLE_PAUSE = "com.necromagik.purehub.ACTION_TOGGLE_PAUSE"

        @Volatile
        var isRunning = false
            private set

        var onServiceStateChanged: ((Boolean) -> Unit)? = null
    }

    private var userService: IPureHubService? = null
    private var isPaused = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.necromagik.purehub",
            "com.necromagik.purehub.service.PureHubUserService"
        )
    ).daemon(false).processNameSuffix("service").debuggable(true).version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "[UserService] Подключен биндер :service")
            // Если тумблер уже выключен, немедленно гасим поднявшийся демон
            if (!ProcessManager.isMusicTargetEnabled(this@PureHubForegroundService)) {
                Log.w(TAG, "[UserService] Тумблер выключен! Останавливаем демон и сервис.")
                stopSelf()
                return
            }

            // Оставляем только самый последний процесс :service, убивая старые дубли
            ProcessManager.deduplicateMusicServiceProcesses()

            userService = IPureHubService.Stub.asInterface(binder)
            userService?.startVolumeListener()
            updateNotification()
            isRunning = true
            onServiceStateChanged?.invoke(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "[UserService] Биндер :service отключен")
            userService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!ProcessManager.isMusicTargetEnabled(this)) {
            Log.w(TAG, "[onCreate] Запуск без разрешения тумблера — самоуничтожение")
            ProcessManager.killOldZombieProcesses()
            stopSelf()
            return
        }

        isRunning = true
        onServiceStateChanged?.invoke(true)

        try {
            // Перед привязкой нового инстанса зачищаем зависшие старые копии
            ProcessManager.killOldZombieProcesses()
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            Log.e(TAG, "[onCreate] Ошибка привязки UserService: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ProcessManager.isMusicTargetEnabled(this)) {
            Log.w(TAG, "[onStartCommand] Целевой тумблер выключен! Гасим себя и зомби.")
            ProcessManager.killOldZombieProcesses()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            isPaused = !isPaused
            userService?.setPaused(isPaused)
            updateNotification()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, PureHubForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val smallIcon = if (isPaused) R.drawable.ic_stat_pause else R.drawable.ic_stat_play
        val title = if (isPaused) "Управление треками: На паузе" else "Управление треками: Активно"
        val subtitle = if (isPaused) "Нажмите, чтобы включить перехват кнопок" else "Удержание Vol+ / Vol- переключает треки"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setColor(0xFF00E676.toInt())
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(togglePendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                if (isPaused) R.drawable.ic_stat_play else R.drawable.ic_stat_pause,
                if (isPaused) "Возобновить" else "Пауза",
                togglePendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PureHUB Фоновый Сервис",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус перехвата кнопок громкости"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "[onDestroy] Остановка сервиса и зачистка демонов :service")
        isRunning = false
        onServiceStateChanged?.invoke(false)
        try {
            userService?.stopVolumeListener()
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Throwable) {}
        ProcessManager.killOldZombieProcesses()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
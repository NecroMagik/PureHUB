package com.necromagik.purehub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.*
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.necromagik.purehub.MainActivity
import com.necromagik.purehub.R
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CaffeineService : Service() {

    companion object {
        private const val TAG = "PureHUB-Caffeine"
        private const val WAKELOCK_TAG = "PureHUB:CaffeineServiceWakeLock"
        private const val CHANNEL_ID = "purehub_caffeine_channel"
        private const val NOTIFICATION_ID = 103

        const val ACTION_START = "com.necromagik.purehub.ACTION_CAFFEINE_START"
        const val ACTION_STOP = "com.necromagik.purehub.ACTION_CAFFEINE_STOP"
        const val EXTRA_MINUTES = "extra_minutes"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var remainingSecondsTotal = 0
            private set

        @Volatile
        var currentMinutes = 0
            private set

        private val tickListeners = CopyOnWriteArrayList<() -> Unit>()

        fun addTickListener(listener: () -> Unit) {
            tickListeners.add(listener)
        }

        fun removeTickListener(listener: () -> Unit) {
            tickListeners.remove(listener)
        }

        fun notifyTick() {
            for (listener in tickListeners) {
                try {
                    listener.invoke()
                } catch (_: Throwable) {}
            }
        }

        fun requestTileUpdate(context: Context) {
            try {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, CaffeineTileService::class.java)
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка requestListeningState: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var tickerTask: ScheduledFuture<*>? = null
    private var warningTask: ScheduledFuture<*>? = null
    private var turnOffTask: ScheduledFuture<*>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "[Service] Остановка службы")
                stopSelf()
            }
            ACTION_START -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 3)
                startCaffeine(minutes)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCaffeine(minutes: Int) {
        cancelScheduledTasks()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            stopSelf()
            return
        }

        if (wakeLock == null) {
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        isRunning = true
        currentMinutes = minutes
        remainingSecondsTotal = minutes * 60

        startForeground(NOTIFICATION_ID, buildNotification(formatRemainingTime()))
        notifyTick()
        requestTileUpdate(this)

        tickerTask = scheduler.scheduleAtFixedRate({
            if (remainingSecondsTotal > 0) {
                remainingSecondsTotal--
                mainHandler.post {
                    if (remainingSecondsTotal <= 60 || remainingSecondsTotal % 30 == 0) {
                        updateNotification()
                    }
                    notifyTick()
                }
            }
        }, 1, 1, TimeUnit.SECONDS)

        val warningDelaySec = (minutes * 60L - 15L).coerceAtLeast(0L)
        warningTask = scheduler.schedule({
            triggerPreShutdownWarning()
        }, warningDelaySec, TimeUnit.SECONDS)

        turnOffTask = scheduler.schedule({
            performScreenTurnOff()
        }, minutes * 60L, TimeUnit.SECONDS)
    }

    private fun triggerPreShutdownWarning() {
        mainHandler.post {
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(applicationContext, notificationUri)?.play()
            } catch (_: Throwable) {}

            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    manager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Throwable) {}

            Toast.makeText(applicationContext, "Экран отключится через 15 секунд", Toast.LENGTH_LONG).show()
        }
    }

    private fun performScreenTurnOff() {
        mainHandler.post {
            releaseWakeLockInternal()
            val (code, _) = SoundController.execShizukuCommand("input keyevent 26")
            if (code != 0) {
                SoundController.execShizukuCommand("cmd statusbar collapse; input keyevent 26")
            }
            stopSelf()
        }
    }

    private fun releaseWakeLockInternal() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun cancelScheduledTasks() {
        tickerTask?.cancel(false)
        warningTask?.cancel(false)
        turnOffTask?.cancel(false)
        tickerTask = null
        warningTask = null
        turnOffTask = null
    }

    private fun formatRemainingTime(): String {
        return if (remainingSecondsTotal < 60) {
            "${remainingSecondsTotal}с"
        } else {
            "${(remainingSecondsTotal + 59) / 60}м"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Режим Кофеин",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(timeRemaining: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CaffeineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            104,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Кофеин активен: $timeRemaining")
            .setContentText("Экран удерживается включенным")
            .setSmallIcon(R.drawable.ic_notification_coffee)
            .setColor(0xFF00E676.toInt())
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification_coffee, "Выключить", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(formatRemainingTime()))
    }

    override fun onDestroy() {
        cancelScheduledTasks()
        releaseWakeLockInternal()
        isRunning = false
        remainingSecondsTotal = 0
        currentMinutes = 0
        notifyTick()
        requestTileUpdate(this)
        scheduler.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
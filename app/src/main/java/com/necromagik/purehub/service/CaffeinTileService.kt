package com.necromagik.purehub.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.necromagik.purehub.R

class CaffeineTileService : TileService() {

    companion object {
        private const val TAG = "PureHUB-Tile"
        private var lastClickTime = 0L
        private const val FRAME_DELAY_MS = 450L // Щадящий темп для плавности без фризов SystemUI
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var animFrameIndex = 0
    private var isAnimationRunning = false

    private val steamFrames = intArrayOf(
        R.drawable.ic_caffeine_steam_1,
        R.drawable.ic_caffeine_steam_2,
        R.drawable.ic_caffeine_steam_3,
        R.drawable.ic_caffeine_steam_4,
        R.drawable.ic_caffeine_steam_5
    )

    private val steamRunnable = object : Runnable {
        override fun run() {
            if (!CaffeineService.isRunning) {
                stopSteamAnimation()
                renderInactive()
                return
            }

            if (animFrameIndex < steamFrames.size - 1) {
                animFrameIndex++
                updateTileDisplay()
                mainHandler.postDelayed(this, 120L) // Быстрый шаг появления (120 мс)
            } else {
                // Достигли последнего кадра с 3 линиями пара — останавливаемся
                isAnimationRunning = false
            }
        }
    }

    private val tickListener = {
        // Если анимация активна, она сама обновляет тайл в цикле.
        // Если анимация на паузе — обновляем состояние напрямую.
        if (!isAnimationRunning) {
            updateTileDisplay()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        CaffeineService.addTickListener(tickListener)
        updateTileDisplay()
        if (CaffeineService.isRunning) {
            startSteamAnimation()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        CaffeineService.removeTickListener(tickListener)
        stopSteamAnimation()
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.uptimeMillis()
        val steps = CaffeinePreferences.getTimerSteps(this)
        val isRunning = CaffeineService.isRunning
        val runningMinutes = CaffeineService.currentMinutes

        Log.i(TAG, "[Click] isRunning=$isRunning, runningMinutes=$runningMinutes, steps=$steps")

        if (!isRunning) {
            val firstStep = steps.firstOrNull() ?: 3
            Log.i(TAG, "[Click] Первичный запуск на $firstStep мин")
            lastClickTime = now
            renderOptimisticActive(firstStep)
            startServiceWithMinutes(firstStep)
            startSteamAnimation()
        } else {
            val diff = now - lastClickTime
            Log.d(TAG, "[Click] Повторный клик через ${diff}мс")

            if (diff <= 10_000L) {
                val currentIndex = steps.indexOf(runningMinutes)
                val nextIndex = if (currentIndex != -1) currentIndex + 1 else 0

                if (nextIndex >= steps.size) {
                    Log.i(TAG, "[Click] Конец списка шагов. Выключение")
                    lastClickTime = 0L
                    stopSteamAnimation()
                    renderInactive()
                    stopCaffeineService()
                } else {
                    val nextMinutes = steps[nextIndex]
                    Log.i(TAG, "[Click] Переключение шага: $runningMinutes мин -> $nextMinutes мин")
                    lastClickTime = now
                    renderOptimisticActive(nextMinutes)
                    startServiceWithMinutes(nextMinutes)
                }
            } else {
                Log.i(TAG, "[Click] Пауза > 10с. Полное выключение")
                lastClickTime = 0L
                stopSteamAnimation()
                renderInactive()
                stopCaffeineService()
            }
        }
    }

    private fun startSteamAnimation() {
        isAnimationRunning = true
        animFrameIndex = 0
        mainHandler.removeCallbacks(steamRunnable)
        mainHandler.post(steamRunnable)
    }

    private fun stopSteamAnimation() {
        isAnimationRunning = false
        mainHandler.removeCallbacks(steamRunnable)
    }

    private fun renderOptimisticActive(minutes: Int) {
        val tile = qsTile ?: return
        try {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Кофеин: ${minutes}м"
            tile.subtitle = "Повтор: смена (<10с)"
            tile.icon = Icon.createWithResource(this, steamFrames[0])
            tile.updateTile()
        } catch (_: Throwable) {}
    }

    private fun renderInactive() {
        val tile = qsTile ?: return
        try {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Кофеин"
            tile.subtitle = "Выключен"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_caffeine_idle)
            tile.updateTile()
        } catch (_: Throwable) {}
    }

    private fun updateTileDisplay() {
        val tile = qsTile ?: return
        try {
            if (CaffeineService.isRunning) {
                val remSec = CaffeineService.remainingSecondsTotal
                val timeText = if (remSec < 60) "${remSec}с" else "${(remSec + 59) / 60}м"

                tile.state = Tile.STATE_ACTIVE
                tile.label = "Кофеин: $timeText"
                tile.subtitle = if (remSec < 60) "Осталось $timeText" else "Клик: выключить"
                tile.icon = Icon.createWithResource(this, steamFrames[animFrameIndex])
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Кофеин"
                tile.subtitle = "Выключен"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_caffeine_idle)
            }
            tile.updateTile()
        } catch (_: Throwable) {}
    }

    private fun startServiceWithMinutes(minutes: Int) {
        val intent = Intent(this, CaffeineService::class.java).apply {
            action = CaffeineService.ACTION_START
            putExtra(CaffeineService.EXTRA_MINUTES, minutes)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                val pendingIntent = PendingIntent.getForegroundService(
                    this,
                    103,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send(options.toBundle())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[Service] Ошибка запуска: ${e.message}")
            try {
                startForegroundService(intent)
            } catch (_: Throwable) {}
        }
    }

    private fun stopCaffeineService() {
        val intent = Intent(this, CaffeineService::class.java).apply {
            action = CaffeineService.ACTION_STOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getService(
                    this,
                    104,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send()
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[Service] Ошибка остановки: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopSteamAnimation()
        CaffeineService.removeTickListener(tickListener)
        super.onDestroy()
    }
}
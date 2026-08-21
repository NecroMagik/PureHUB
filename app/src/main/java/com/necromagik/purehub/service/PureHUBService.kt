package com.necromagik.purehub.service

import android.os.SystemClock
import androidx.annotation.Keep
import com.necromagik.purehub.IPureHubService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@Keep
class PureHubUserService : IPureHubService.Stub {

    constructor() : super()

    companion object {
        private const val TAG = "PureHUB-Diag"

        private val logV: Method? = runCatching { Class.forName("android.util.Log").getMethod("v", String::class.java, String::class.java) }.getOrNull()
        private val logD: Method? = runCatching { Class.forName("android.util.Log").getMethod("d", String::class.java, String::class.java) }.getOrNull()
        private val logI: Method? = runCatching { Class.forName("android.util.Log").getMethod("i", String::class.java, String::class.java) }.getOrNull()
        private val logW: Method? = runCatching { Class.forName("android.util.Log").getMethod("w", String::class.java, String::class.java) }.getOrNull()
        private val logE: Method? = runCatching { Class.forName("android.util.Log").getMethod("e", String::class.java, String::class.java) }.getOrNull()

        private fun logToSystem(level: String, msg: String) {
            val formatted = "[$TAG] [$level] $msg"
            println(formatted)
            try {
                when (level.lowercase()) {
                    "v" -> logV?.invoke(null, TAG, msg)
                    "d" -> logD?.invoke(null, TAG, msg)
                    "i" -> logI?.invoke(null, TAG, msg)
                    "w" -> logW?.invoke(null, TAG, msg)
                    "e" -> logE?.invoke(null, TAG, msg)
                    else -> logI?.invoke(null, TAG, msg)
                }
            } catch (_: Throwable) {}
        }
    }

    @Volatile
    private var isRunning = false
    private var eventThread: Thread? = null
    private var getEventProcess: Process? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var longPressTask: ScheduledFuture<*>? = null

    private val longPressThresholdMs = 420L
    private val debounceCooldownMs = 200L

    private var activeKey: String? = null
    private var pressStartTime = 0L
    private var originalVolume = -1
    private val isTriggered = AtomicBoolean(false)
    private var lastActionTime = 0L

    init {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
                logToSystem("i", "[Init] HiddenApiBypass успешно применен")
            }
        } catch (e: Throwable) {
            logToSystem("w", "[Init] HiddenApiBypass warning: ${e.message}")
        }
    }

    override fun startVolumeListener() {
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { -1 }
        logToSystem("i", "[UserService] startVolumeListener() запущен (PID $pid)")
        if (isRunning) return
        isRunning = true

        eventThread = Thread({
            runDirectEventLoop()
        }, "PureHub-DirectEvent").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stopVolumeListener() {
        isRunning = false
        resetState("stopVolumeListener")
        try {
            getEventProcess?.destroy()
        } catch (_: Throwable) {}
        eventThread?.interrupt()
        eventThread = null
    }

    override fun isListening(): Boolean = isRunning

    override fun destroy() {
        logToSystem("i", "[UserService] destroy() вызван")
        stopVolumeListener()
        scheduler.shutdownNow()
        exitProcess(0)
    }

    private fun runDirectEventLoop() {
        try {
            val process = ProcessBuilder("getevent", "-l").start()
            getEventProcess = process

            val inputStream: InputStream = process.inputStream
            val lineBuffer = StringBuilder()
            val byteBuffer = ByteArray(512)
            logToSystem("i", "[UserService] getevent запущен, слушаем ввод...")

            while (isRunning) {
                val bytesRead = inputStream.read(byteBuffer)
                if (bytesRead == -1) break

                for (i in 0 until bytesRead) {
                    val ch = byteBuffer[i].toInt().toChar()
                    if (ch == '\n') {
                        processLine(lineBuffer.toString())
                        lineBuffer.setLength(0)
                    } else if (ch != '\r') {
                        lineBuffer.append(ch)
                    }
                }
            }
        } catch (e: Throwable) {
            if (isRunning) {
                logToSystem("e", "[Loop] Ошибка getevent: ${e.message}")
            }
        } finally {
            try {
                getEventProcess?.destroy()
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    private fun processLine(line: String) {
        if (!line.contains("EV_KEY")) return

        val tokens = line.trim().split(Regex("\\s+"))
        val keyIndex = tokens.indexOfFirst { it == "KEY_VOLUMEUP" || it == "KEY_VOLUMEDOWN" }
        if (keyIndex == -1 || keyIndex + 1 >= tokens.size) return

        val keyName = tokens[keyIndex]
        val action = tokens[keyIndex + 1]

        val isDown = action.equals("DOWN", ignoreCase = true)
        val isUp = action.equals("UP", ignoreCase = true)
        if (!isDown && !isUp) return

        val currentKey = if (keyName == "KEY_VOLUMEUP") "VOL_UP" else "VOL_DOWN"
        val now = SystemClock.uptimeMillis()

        if (isDown) {
            if (now - lastActionTime < debounceCooldownMs) return
            if (activeKey != null) return

            activeKey = currentKey
            pressStartTime = now
            isTriggered.set(false)

            originalVolume = AudioServiceController.getMusicVolume()
            logToSystem("d", "[Event] $currentKey НАЖАТА. Зафиксированная громкость: $originalVolume")

            longPressTask?.cancel(false)
            longPressTask = scheduler.schedule({
                handleLongPress(currentKey)
            }, longPressThresholdMs, TimeUnit.MILLISECONDS)

        } else if (isUp) {
            if (activeKey == currentKey) {
                val duration = now - pressStartTime
                longPressTask?.cancel(false)

                if (isTriggered.get()) {
                    logToSystem("d", "[Event] $currentKey ОТПУЩЕНА после long press (${duration}мс)")
                    restoreVolume(originalVolume)
                } else {
                    logToSystem("d", "[Event] $currentKey ОТПУЩЕНА. Короткий клик (${duration}мс)")
                }

                resetState("KeyUpProcessed")
                lastActionTime = SystemClock.uptimeMillis()
            } else if (activeKey != null) {
                longPressTask?.cancel(false)
                resetState("KeyMismatchReset")
            }
        }
    }

    private fun handleLongPress(key: String) {
        if (activeKey != key || isTriggered.getAndSet(true)) return

        logToSystem("i", "[Action] Сработало долгое удержание для $key")
        try {
            if (key == "VOL_UP") {
                MediaCommander.nextTrack()
            } else if (key == "VOL_DOWN") {
                MediaCommander.previousTrack()
            }
        } catch (e: Throwable) {
            logToSystem("e", "[Media] Ошибка переключения трека: ${e.message}")
        }

        restoreVolume(originalVolume)
    }

    private fun restoreVolume(volume: Int) {
        if (volume >= 0) {
            try {
                AudioServiceController.setMusicVolume(volume)
            } catch (e: Throwable) {
                logToSystem("e", "[Audio] Ошибка восстановления громкости: ${e.message}")
            }
        }
    }

    private fun resetState(reason: String) {
        activeKey = null
        pressStartTime = 0L
        isTriggered.set(false)
        originalVolume = -1
    }
}
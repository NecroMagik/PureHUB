package com.necromagik.purehub.service

import android.content.Context
import android.os.Process
import android.util.Log

object ProcessManager {
    private const val TAG = "PureHUB-ProcWatch"
    private const val PACKAGE_NAME = "com.necromagik.purehub"
    private const val PREFS_NAME = "purehub_service_prefs"
    private const val KEY_MUSIC_ENABLED = "pref_music_control_enabled"

    fun setMusicTargetEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MUSIC_ENABLED, enabled)
            .commit()
    }

    fun isMusicTargetEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUSIC_ENABLED, false)
    }

    /**
     * Возвращает список всех PID фонового демона :service (отсортированный по возрастанию)
     */
    fun getMusicServicePids(): List<Int> {
        if (!SoundController.checkShizukuReady()) return emptyList()
        val (code, out) = SoundController.execShizukuCommand("ps -ef | grep '$PACKAGE_NAME:service' | grep -v grep")
        if (code != 0 || out.isBlank()) return emptyList()

        return out.lines().mapNotNull { line ->
            val tokens = line.trim().split("\\s+".toRegex())
            if (tokens.size >= 2) tokens[1].toIntOrNull() else null
        }
    }

    /**
     * Проверяет, реально ли в системе крутится фоновый процесс управления треками
     */
    fun isMusicServiceProcessAlive(): Boolean {
        return getMusicServicePids().isNotEmpty()
    }

    /**
     * Если запущено несколько процессов :service, убивает все старые и оставляет только самый последний
     */
    fun deduplicateMusicServiceProcesses() {
        try {
            val pids = getMusicServicePids()
            if (pids.size > 1) {
                val newestPid = pids.maxOrNull() ?: return
                Log.w(TAG, "[Watchdog] Обнаружено наслоение процессов :service ($pids). Оставляем последний PID=$newestPid")
                for (pid in pids) {
                    if (pid != newestPid) {
                        Log.w(TAG, "[Watchdog] Убийство старого дубликата :service PID=$pid")
                        SoundController.execShizukuCommand("kill -9 $pid")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[Watchdog] Ошибка дедупликации: ${e.message}", e)
        }
    }

    /**
     * Полное уничтожение всех фоновых процессов :service перед чистым стартом или при остановке
     */
    fun killOldZombieProcesses(currentUiPid: Int = Process.myPid()) {
        try {
            Log.i(TAG, "[Watchdog] Зачистка всех старых процессов :service (UI PID=$currentUiPid)")
            SoundController.execShizukuCommand("pkill -9 -f '$PACKAGE_NAME:service'")

            val (code, psOut) = SoundController.execShizukuCommand("ps -ef | grep -E '$PACKAGE_NAME:service|app_process.*$PACKAGE_NAME' | grep -v grep")
            if (code == 0 && psOut.isNotBlank()) {
                psOut.lines().forEach { line ->
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size >= 2) {
                        val pid = tokens[1].toIntOrNull()
                        if (pid != null && pid != currentUiPid) {
                            Log.w(TAG, "[Watchdog] Уничтожение зомби PID=$pid ($line)")
                            SoundController.execShizukuCommand("kill -9 $pid")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[Watchdog] Ошибка очистки зомби: ${e.message}", e)
        }
    }

    /**
     * Точечный Force Stop: уничтожает ВСЕ фоновые процессы управления музыкой,
     * снимает слушатели кнопок, но НЕ ЗАКРЫВАЕТ само приложение!
     */
    fun forceStopMusicProcessesOnly(context: Context) {
        val myUiPid = Process.myPid()
        Log.e(TAG, "=== [FORCE STOP MUSIC] Точечное завершение фоновых демонов треков ===")
        try {
            // 1. Помечаем в настройках, что музыка принудительно отключена
            setMusicTargetEnabled(context, false)

            // 2. Останавливаем саму службу Android
            try {
                context.stopService( android.content.Intent(context, PureHubForegroundService::class.java))
            } catch (_: Throwable) {}

            // 3. Убиваем все демоны :service и app_process
            SoundController.execShizukuCommand("pkill -9 -f '$PACKAGE_NAME:service'")
            SoundController.execShizukuCommand("pkill -9 -f 'app_process.*$PACKAGE_NAME'")

            // 4. Дополнительно ищем и гасим любые PID пакета кроме текущего UI
            val (code, psOut) = SoundController.execShizukuCommand("ps -ef | grep '$PACKAGE_NAME' | grep -v grep")
            if (code == 0 && psOut.isNotBlank()) {
                psOut.lines().forEach { line ->
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size >= 2) {
                        val pid = tokens[1].toIntOrNull()
                        if (pid != null && pid != myUiPid) {
                            Log.w(TAG, "[FORCE STOP MUSIC] Убит процесс PID=$pid")
                            SoundController.execShizukuCommand("kill -9 $pid")
                        }
                    }
                }
            }
            Log.i(TAG, "=== [FORCE STOP MUSIC] Все фоновые процессы музыки зачищены. UI продолжает работать ===")
        } catch (e: Throwable) {
            Log.e(TAG, "[FORCE STOP MUSIC] Ошибка: ${e.message}", e)
        }
    }

    fun logRunningProcessesSnapshot(stage: String) {
        val myPid = Process.myPid()
        Log.i(TAG, "================ [SNAPSHOT: $stage] ================")
        Log.i(TAG, "Контекст вызова: PID=$myPid, UID=${Process.myUid()}")

        if (!SoundController.checkShizukuReady()) return

        try {
            val (psCode, psOut) = SoundController.execShizukuCommand("ps -ef | grep -E '$PACKAGE_NAME|app_process' | grep -v grep")
            if (psCode == 0 && psOut.isNotBlank()) {
                Log.i(TAG, "--- Активные процессы ($PACKAGE_NAME / app_process) ---")
                psOut.lines().forEach { line ->
                    if (line.isNotBlank()) Log.d(TAG, "  $line")
                }
            } else {
                Log.d(TAG, "Фоновых процессов не обнаружено")
            }
        } catch (_: Throwable) {}
        Log.i(TAG, "====================================================")
    }
}
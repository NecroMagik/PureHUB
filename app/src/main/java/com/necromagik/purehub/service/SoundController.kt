package com.necromagik.purehub.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

private const val TAG_LOG = "PureHUB-SoundEngine"

enum class TableType { GLOBAL, SYSTEM }

enum class SoundCategory(val title: String) {
    SYSTEM("Блокировка и безопасность"),
    FEEDBACK("Тактильный отклик и клики"),
    BATTERY_CHARGING("Питание и зарядка"),
    CAMERA("Камера и мультимедиа"),
    DOCK("Док-станции и аксессуары"),
    VIVO_CUSTOM("Специальные эффекты Vivo"),
    OTHER("Прочие звуки системы")
}

data class DiscoveredSound(
    val fileName: String,
    val localizedTitle: String,
    val category: SoundCategory,
    val currentPath: String?,
    val isCustom: Boolean,
    val settingsKey: String? = null,
    val tableType: TableType = TableType.GLOBAL
)

object SoundController {

    const val TUNER_PACKAGE = "com.zacharee1.systemuituner"

    data class SoundMapping(
        val key: String,
        val category: SoundCategory,
        val table: TableType = TableType.GLOBAL
    )

    private val KNOWN_SYSTEM_KEYS = mapOf(
        // Стандартные AOSP ключи (Settings.Global)
        "Lock.ogg" to SoundMapping("lock_sound", SoundCategory.SYSTEM, TableType.GLOBAL),
        "Unlock.ogg" to SoundMapping("unlock_sound", SoundCategory.SYSTEM, TableType.GLOBAL),
        "Trusted.ogg" to SoundMapping("trusted_sound", SoundCategory.SYSTEM, TableType.GLOBAL),
        "ChargingStarted.ogg" to SoundMapping("charging_started_sound", SoundCategory.BATTERY_CHARGING, TableType.GLOBAL),
        "WirelessChargingStarted.ogg" to SoundMapping("wireless_charging_started_sound", SoundCategory.BATTERY_CHARGING, TableType.GLOBAL),
        "LowBattery.ogg" to SoundMapping("low_battery_sound", SoundCategory.BATTERY_CHARGING, TableType.GLOBAL),
        "Dock.ogg" to SoundMapping("car_dock_sound", SoundCategory.DOCK, TableType.GLOBAL),
        "Undock.ogg" to SoundMapping("car_undock_sound", SoundCategory.DOCK, TableType.GLOBAL),
        "DeskDock.ogg" to SoundMapping("desk_dock_sound", SoundCategory.DOCK, TableType.GLOBAL),
        "DeskUndock.ogg" to SoundMapping("desk_undock_sound", SoundCategory.DOCK, TableType.GLOBAL),
        "Effect_Tick.ogg" to SoundMapping("system_sound_effect_tick", SoundCategory.FEEDBACK, TableType.GLOBAL),
        "KeypressStandard.ogg" to SoundMapping("system_sound_keypress_standard", SoundCategory.FEEDBACK, TableType.GLOBAL),
        "KeypressSpacebar.ogg" to SoundMapping("system_sound_keypress_spacebar", SoundCategory.FEEDBACK, TableType.GLOBAL),
        "KeypressDelete.ogg" to SoundMapping("system_sound_keypress_delete", SoundCategory.FEEDBACK, TableType.GLOBAL),
        "KeypressReturn.ogg" to SoundMapping("system_sound_keypress_return", SoundCategory.FEEDBACK, TableType.GLOBAL),
        "camera_click.ogg" to SoundMapping("camera_click", SoundCategory.CAMERA, TableType.GLOBAL),
        "camera_focus.ogg" to SoundMapping("camera_focus", SoundCategory.CAMERA, TableType.GLOBAL),
        "VideoRecord.ogg" to SoundMapping("video_record", SoundCategory.CAMERA, TableType.GLOBAL),

        // Специфичные для Vivo/OriginOS ключи (Settings.System)
        "global_delete.ogg" to SoundMapping("vivo_delete_sound_path", SoundCategory.VIVO_CUSTOM, TableType.SYSTEM),
        "LowBattery2.ogg" to SoundMapping("vivo_low_battery_sound2", SoundCategory.BATTERY_CHARGING, TableType.SYSTEM),
        "MusicPlayer_open.ogg" to SoundMapping("vivo_musicplayer_open", SoundCategory.VIVO_CUSTOM, TableType.SYSTEM),
        "MusicPlayer_close.ogg" to SoundMapping("vivo_musicplayer_close", SoundCategory.VIVO_CUSTOM, TableType.SYSTEM),
        "SuperX_a.ogg" to SoundMapping("vivo_superx_a_sound", SoundCategory.VIVO_CUSTOM, TableType.SYSTEM),
        "SuperX_b.ogg" to SoundMapping("vivo_superx_b_sound", SoundCategory.VIVO_CUSTOM, TableType.SYSTEM),
        "success.ogg" to SoundMapping("vivo_alarm_component_sound", SoundCategory.VIVO_CUSTOM, TableType.SYSTEM)
    )

    fun isTunerInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TUNER_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun checkShizukuReady(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG_LOG, "[Auth] Ошибка проверки Shizuku: ${e.message}")
            false
        }
    }

    fun execShizukuCommand(cmd: String): Pair<Int, String> {
        if (!checkShizukuReady()) {
            return -1 to "Shizuku not ready"
        }
        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

            val output = StringBuilder()
            val errors = StringBuilder()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }

            BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                var line: String?
                while (errorReader.readLine().also { line = it } != null) {
                    errors.append(line).append("\n")
                }
            }

            val exitCode = process.waitFor()
            process.destroy()

            val resultOut = output.toString().trim()
            val resultErr = errors.toString().trim()

            exitCode to if (resultOut.isNotBlank()) resultOut else resultErr
        } catch (e: Exception) {
            Log.e(TAG_LOG, "[Shell-Error] Ошибка exec: $cmd", e)
            -1 to (e.message ?: "Unknown error")
        }
    }

    private fun loadSettingsTable(tableName: String): Map<String, String> {
        val (code, out) = execShizukuCommand("settings list $tableName")
        if (code != 0 || out.isBlank()) return emptyMap()

        val map = mutableMapOf<String, String>()
        out.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                map[k] = v
            }
        }
        return map
    }

    fun putSetting(table: TableType, key: String, value: String): Boolean {
        val tableName = if (table == TableType.GLOBAL) "global" else "system"
        Log.i(TAG_LOG, "[Settings.$tableName] Запись ключа '$key' -> '$value'")
        val (code, _) = execShizukuCommand("settings put $tableName $key '$value'")
        return code == 0
    }

    fun deleteSetting(table: TableType, key: String): Boolean {
        val tableName = if (table == TableType.GLOBAL) "global" else "system"
        val (code, _) = execShizukuCommand("settings delete $tableName $key")
        return code == 0
    }

    fun setSystemLockSoundsEnabled(enabled: Boolean): Boolean {
        val flag = if (enabled) "1" else "0"
        val (code, _) = execShizukuCommand("settings put system lockscreen_sounds_enabled $flag")
        return code == 0
    }

    private fun getPublicSoundsDir(): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(musicDir, "PureHUB")
    }

    /**
     * Сканирование доступных системных звуков
     */
    suspend fun scanAndLogSystemSoundsAsync(context: Context): List<DiscoveredSound> = withContext(Dispatchers.IO) {
        val globalSettings = loadSettingsTable("global")
        val systemSettings = loadSettingsTable("system")
        val foundFiles = mutableSetOf<String>()

        val pathsToScan = listOf(
            "/product/media/audio/ui",
            "/system/media/audio/ui",
            "/system_ext/media/audio/ui",
            "/vendor/media/audio/ui"
        )

        for (dir in pathsToScan) {
            val (code, out) = execShizukuCommand("ls $dir")
            if (code == 0 && out.isNotBlank()) {
                val lines = out.lines().map { it.trim() }.filter {
                    it.endsWith(".ogg", ignoreCase = true) || it.endsWith(".wav", ignoreCase = true)
                }
                foundFiles.addAll(lines)
            }
        }

        if (foundFiles.isEmpty()) {
            foundFiles.addAll(KNOWN_SYSTEM_KEYS.keys)
        }

        val soundsDir = getPublicSoundsDir()

        foundFiles.sorted().map { fileName ->
            val mapping = KNOWN_SYSTEM_KEYS[fileName]
            val settingsKey = mapping?.key ?: fileName.substringBeforeLast('.').lowercase()
            val category = mapping?.category ?: detectCategory(fileName)
            val tableType = mapping?.table ?: TableType.GLOBAL

            val customFile = File(soundsDir, "ui_sound_${settingsKey}.ogg")
            val currentTableValue = if (tableType == TableType.GLOBAL) globalSettings[settingsKey] else systemSettings[settingsKey]
            val isCustom = (customFile.exists() && customFile.length() > 0) ||
                    (currentTableValue != null && (currentTableValue.startsWith("/storage/") || currentTableValue.startsWith("/data/")))
            val currentPath = if (customFile.exists()) customFile.absolutePath else currentTableValue

            DiscoveredSound(
                fileName = fileName,
                localizedTitle = getLocalizedTitle(fileName),
                category = category,
                currentPath = currentPath,
                isCustom = isCustom,
                settingsKey = settingsKey,
                tableType = tableType
            )
        }
    }

    private fun detectCategory(name: String): SoundCategory {
        val lower = name.lowercase()
        return when {
            lower.startsWith("vivo_") -> SoundCategory.VIVO_CUSTOM
            lower.contains("lock") || lower.contains("trust") -> SoundCategory.SYSTEM
            lower.contains("tick") || lower.contains("click") || lower.contains("keypress") -> SoundCategory.FEEDBACK
            lower.contains("charge") || lower.contains("battery") -> SoundCategory.BATTERY_CHARGING
            lower.contains("camera") || lower.contains("video") || lower.contains("focus") -> SoundCategory.CAMERA
            lower.contains("dock") -> SoundCategory.DOCK
            else -> SoundCategory.OTHER
        }
    }

    private fun getLocalizedTitle(fileName: String): String {
        return when (fileName) {
            "Lock.ogg" -> "Блокировка экрана"
            "Unlock.ogg" -> "Разблокировка экрана"
            "Trusted.ogg" -> "Доверенное устройство (Smart Lock)"
            "Effect_Tick.ogg" -> "Клик интерфейса (Effect_Tick)"
            "KeypressStandard.ogg" -> "Нажатие клавиши (Standard)"
            "KeypressSpacebar.ogg" -> "Нажатие пробела (Spacebar)"
            "KeypressDelete.ogg" -> "Удаление символа (Delete)"
            "KeypressReturn.ogg" -> "Ввод (Return)"
            "LowBattery.ogg" -> "Низкий заряд батареи"
            "ChargingStarted.ogg" -> "Подключение зарядного устройства"
            "WirelessChargingStarted.ogg" -> "Беспроводная зарядка"
            "camera_click.ogg" -> "Затвор камеры"
            "camera_focus.ogg" -> "Фокусировка камеры"
            "VideoRecord.ogg" -> "Старт записи видео"
            "Dock.ogg" -> "Автомобильный док"
            "Undock.ogg" -> "Отключение дока"
            "DeskDock.ogg" -> "Настольная станция"
            "DeskUndock.ogg" -> "Отключение станции"
            "global_delete.ogg" -> "Звук удаления (Vivo)"
            "LowBattery2.ogg" -> "Низкий заряд батареи 2 (Vivo)"
            "MusicPlayer_open.ogg" -> "Открытие плеера (Vivo)"
            "MusicPlayer_close.ogg" -> "Закрытие плеера (Vivo)"
            "SuperX_a.ogg" -> "SuperX звук A (Vivo)"
            "SuperX_b.ogg" -> "SuperX звук B (Vivo)"
            "success.ogg" -> "Будильник: Успех (Vivo)"
            else -> fileName.substringBeforeLast('.')
        }
    }

    /**
     * Копирует файл через временный кэш в публичную директорию Music/PureHUB с помощью Shizuku
     */
    fun installCustomSound(context: Context, targetKey: String, fileUri: Uri, tableType: TableType = TableType.GLOBAL): Boolean {
        val fileName = "ui_sound_${targetKey}.ogg"

        // Используем externalCacheDir вместо cacheDir, чтобы у процесса shell (Shizuku) был доступ на чтение
        val cacheBase = context.externalCacheDir ?: context.cacheDir
        val tempCacheFile = File(cacheBase, "temp_$fileName")

        try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(tempCacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG_LOG, "[Install-Error] Не удалось открыть входной поток: $fileUri")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG_LOG, "[Install-Error] Ошибка чтения во временный файл: ${e.message}", e)
            return false
        }

        val targetDir = getPublicSoundsDir().absolutePath
        val destFilePath = "$targetDir/$fileName"

        // Копируем файл шеллом и выставляем права 644
        val commands = """
            mkdir -p '$targetDir' &&
            cp -f '${tempCacheFile.absolutePath}' '$destFilePath' &&
            chmod 644 '$destFilePath'
        """.trimIndent().replace("\n", " ")

        val (shellCode, shellOut) = execShizukuCommand(commands)

        // Удаляем временный файл после копирования
        if (tempCacheFile.exists()) {
            tempCacheFile.delete()
        }

        if (shellCode != 0) {
            Log.e(TAG_LOG, "[Install-Error] Ошибка копирования файла через Shizuku: $shellOut")
            return false
        }

        val success = putSetting(tableType, targetKey, destFilePath)
        Log.i(TAG_LOG, "[Install] Сохранено в $destFilePath -> $targetKey (success=$success)")
        return success
    }

    fun resetCustomSound(context: Context, targetKey: String, tableType: TableType = TableType.GLOBAL): Boolean {
        return try {
            val targetDir = getPublicSoundsDir().absolutePath
            val destFilePath = "$targetDir/ui_sound_${targetKey}.ogg"

            execShizukuCommand("rm -f '$destFilePath'")
            deleteSetting(tableType, targetKey)
            Log.i(TAG_LOG, "[Reset] Сброшен ключ: $targetKey ($tableType)")
            true
        } catch (e: Exception) {
            Log.e(TAG_LOG, "[Reset-Error] Ошибка сброса: ${e.message}", e)
            false
        }
    }

    fun restartSystemUIWithResult(): Boolean {
        Log.w(TAG_LOG, "[Restart] Перезапуск SystemUI...")
        val (code, out) = execShizukuCommand("am crash com.android.systemui")
        Log.i(TAG_LOG, "[Restart] SystemUI результат: exitCode=$code, output='$out'")
        return code == 0
    }
}
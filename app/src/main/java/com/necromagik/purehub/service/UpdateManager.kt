package com.necromagik.purehub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.necromagik.purehub.BuildConfig
import com.necromagik.purehub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionTag: String,
    val cleanVersion: String,
    val changelog: String,
    val apkDownloadUrl: String?,
    val htmlUrl: String
)

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Available(val info: ReleaseInfo) : UpdateUiState()
    data class Downloading(val progress: Int, val info: ReleaseInfo) : UpdateUiState()
    data class ReadyToInstall(val apkFile: File, val info: ReleaseInfo) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

object UpdateManager {
    private const val TAG = "PureHUB-Updater"
    private const val GITHUB_OWNER = "NecroMagik"
    private const val GITHUB_REPO = "PureHUB"

    private const val CHANNEL_ID = "purehub_updater_channel"
    private const val NOTIFICATION_ID = 707

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Проверка обновлений на GitHub (проверяет сначала Releases, затем Tags)
     * @param showUserFeedback если true — показывает системный баббл/Toast и уведомление
     */
    suspend fun checkForUpdates(context: Context, showUserFeedback: Boolean = true) {
        if (_updateState.value is UpdateUiState.Downloading) return

        _updateState.value = UpdateUiState.Checking
        if (showUserFeedback) {
            showSystemBubble(context, "Проверка обновлений PureHUB...", isOngoing = true)
            postToast(context, "Проверка обновлений...")
        }

        withContext(Dispatchers.IO) {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                Log.i(TAG, "[Check] Текущая версия: $currentVersion (${BuildConfig.VERSION_CODE})")

                // 1. Сначала проверяем список релизов (/releases)
                var latestRelease = fetchFromReleasesApi()

                // 2. Если релизов еще не оформлено, проверяем список тегов (/tags)
                if (latestRelease == null) {
                    Log.i(TAG, "[Check] Релизы не найдены, проверяем ветку тегов (/tags)...")
                    latestRelease = fetchFromTagsApi()
                }

                if (latestRelease == null) {
                    _updateState.value = UpdateUiState.Idle
                    if (showUserFeedback) {
                        cancelSystemBubble(context)
                        postToast(context, "Релизы на GitHub пока не найдены")
                    }
                    return@withContext
                }

                val isNewer = isRemoteVersionNewer(currentVersion, latestRelease.cleanVersion)
                Log.i(TAG, "[Check] Найдена версия на GitHub: ${latestRelease.versionTag} (новее=$isNewer)")

                if (isNewer) {
                    _updateState.value = UpdateUiState.Available(latestRelease)
                    showSystemBubble(
                        context,
                        "Доступно обновление ${latestRelease.versionTag}!",
                        isOngoing = false
                    )
                    if (showUserFeedback) {
                        postToast(context, "Найдено обновление ${latestRelease.versionTag}!")
                    }
                } else {
                    _updateState.value = UpdateUiState.Idle
                    if (showUserFeedback) {
                        cancelSystemBubble(context)
                        postToast(context, "У вас установлена последняя версия ($currentVersion)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Check] Ошибка проверки обновлений", e)
                _updateState.value = UpdateUiState.Error(e.message ?: "Ошибка сети")
                if (showUserFeedback) {
                    cancelSystemBubble(context)
                    postToast(context, "Ошибка проверки обновлений: проверьте сеть")
                }
            }
        }
    }

    /**
     * Скачивание и автоматическая установка APK обновления
     */
    suspend fun downloadAndInstall(context: Context, info: ReleaseInfo) {
        val downloadUrl = info.apkDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            // Если к тегу не прикреплен .apk файл, открываем страницу релиза в браузере
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _updateState.value = UpdateUiState.Downloading(0, info)
                showSystemBubble(context, "Скачивание PureHUB ${info.versionTag}: 0%", isOngoing = true, progress = 0)

                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val apkFile = File(cacheDir, "PureHUB_${info.cleanVersion}.apk")
                if (apkFile.exists()) apkFile.delete()

                var connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "PureHUB-Updater")

                // Обработка редиректов GitHub S3
                val status = connection.responseCode
                if (status in 300..399) {
                    val redirectUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = URL(redirectUrl).openConnection() as HttpURLConnection
                }

                val totalBytes = connection.contentLength
                var downloadedBytes = 0L
                var lastReportedProgress = 0

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress - lastReportedProgress >= 5 || progress == 100) {
                                    lastReportedProgress = progress
                                    _updateState.value = UpdateUiState.Downloading(progress, info)
                                    showSystemBubble(
                                        context,
                                        "Скачивание PureHUB ${info.versionTag}: $progress%",
                                        isOngoing = true,
                                        progress = progress
                                    )
                                }
                            }
                        }
                    }
                }

                _updateState.value = UpdateUiState.ReadyToInstall(apkFile, info)
                showSystemBubble(context, "Установка обновления ${info.versionTag}...", isOngoing = false)
                installApk(context, apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "[Download] Ошибка скачивания APK", e)
                _updateState.value = UpdateUiState.Available(info)
                cancelSystemBubble(context)
                postToast(context, "Ошибка скачивания: ${e.message}")
            }
        }
    }

    /**
     * Устанавливает APK: сначала пробует тихую установку через Shizuku,
     * если Shizuku недоступен — открывает системный установщик пакетов.
     */
    fun installApk(context: Context, apkFile: File) {
        // 1. Попытка быстрой установки через Shizuku Shell (если активен)
        if (SoundController.checkShizukuReady()) {
            Log.i(TAG, "[Install] Запуск тихой установки через Shizuku...")
            postToast(context, "Установка через Shizuku...")
            val (code, out) = SoundController.execShizukuCommand("pm install -r '${apkFile.absolutePath}'")
            if (code == 0 && out.contains("Success", ignoreCase = true)) {
                cancelSystemBubble(context)
                postToast(context, "Обновление успешно установлено!")
                return
            }
            Log.w(TAG, "[Install] Shizuku pm install не сработал ($out), переход к системному установщику")
        }

        // 2. Стандартная установка через PackageInstaller / FileProvider
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            postToast(context, "Разрешите установку обновлений для PureHUB")
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    private fun fetchFromReleasesApi(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PureHUB-Android")
        }

        if (conn.responseCode != 200) return null
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(response)
        if (array.length() == 0) return null

        val latestObj = array.getJSONObject(0)
        val tagName = latestObj.optString("tag_name", "")
        if (tagName.isBlank()) return null

        val body = latestObj.optString("body", "Исправление ошибок и улучшение стабильности.")
        val htmlUrl = latestObj.optString("html_url", "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")

        var apkUrl: String? = null
        val assets = latestObj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", null)
                    break
                }
            }
        }

        // Если в ассетах нет .apk, конструируем прямую ссылку на стандартное имя релиза
        if (apkUrl == null) {
            apkUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/app-release.apk"
        }

        return ReleaseInfo(
            versionTag = tagName,
            cleanVersion = tagName.removePrefix("v").removePrefix("V").trim(),
            changelog = body.ifBlank { "Доступна новая сборка $tagName" },
            apkDownloadUrl = apkUrl,
            htmlUrl = htmlUrl
        )
    }

    private fun fetchFromTagsApi(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/tags")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PureHUB-Android")
        }

        if (conn.responseCode != 200) return null
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(response)
        if (array.length() == 0) return null

        val latestTagObj = array.getJSONObject(0)
        val tagName = latestTagObj.optString("name", "")
        if (tagName.isBlank()) return null

        return ReleaseInfo(
            versionTag = tagName,
            cleanVersion = tagName.removePrefix("v").removePrefix("V").trim(),
            changelog = "Новая версия по тегу $tagName",
            apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/app-release.apk",
            htmlUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tagName"
        )
    }

    /**
     * Сравнивает семантические версии (например, "1.15" и "1.16" или "1.15.1")
     */
    private fun isRemoteVersionNewer(current: String, remote: String): Boolean {
        val cleanCur = current.removePrefix("v").removePrefix("V").trim()
        val cleanRem = remote.removePrefix("v").removePrefix("V").trim()
        if (cleanCur == cleanRem) return false

        val curParts = cleanCur.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val remParts = cleanRem.split(".", "-", "_").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(curParts.size, remParts.size)
        for (i in 0 until maxLen) {
            val c = curParts.getOrElse(i) { 0 }
            val r = remParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun showSystemBubble(
        context: Context,
        message: String,
        isOngoing: Boolean,
        progress: Int? = null
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Обновления PureHUB",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Уведомления о проверке и скачивании обновлений"
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setColor(0xFF00E676.toInt())
                .setContentTitle("PureHUB Обновление")
                .setContentText(message)
                .setOngoing(isOngoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!isOngoing)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            if (progress != null) {
                builder.setSmallIcon(android.R.drawable.stat_sys_download)
                builder.setProgress(100, progress, false)
            } else if (isOngoing) {
                builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                builder.setProgress(100, 0, true)
            }

            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Throwable) {}
    }

    private fun cancelSystemBubble(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    private fun postToast(context: Context, text: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }
}
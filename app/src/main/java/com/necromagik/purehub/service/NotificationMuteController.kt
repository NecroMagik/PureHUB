package com.necromagik.purehub.service

import android.util.Log

object NotificationMuteController {
    private const val TAG = "PureHUB-NotifMute"

    @Volatile
    var isMuted = false
        private set

    var onMuteChangedListener: ((Boolean) -> Unit)? = null

    fun setNotificationsMuted(muted: Boolean): Boolean {
        Log.i(TAG, "=== [Mute] Запрос: targetMuted=$muted (текущий: $isMuted) ===")
        return try {
            if (muted) {
                // 1. Включение DND (Alarms Only) через cmd notification
                val (dndCode, dndOut) = SoundController.execShizukuCommand("cmd notification set_dnd alarms")
                Log.d(TAG, "[Mute] set_dnd alarms -> exitCode=$dndCode, out='$dndOut'")

                // Если прошивка требует полного подавления, резервная команда:
                if (dndCode != 0) {
                    SoundController.execShizukuCommand("cmd notification set_dnd on")
                }

                // 2. Дополнительное глушение громкости уведомлений
                SoundController.execShizukuCommand("cmd media_session volume --stream 5 --set 0")
                SoundController.execShizukuCommand("settings put system notification_vibration_intensity 0")
                SoundController.execShizukuCommand("settings put global zen_mode 3")
                SoundController.execShizukuCommand("settings put global heads_up_notifications_enabled 0")
            } else {
                // 1. Выключение DND
                val (dndCode, dndOut) = SoundController.execShizukuCommand("cmd notification set_dnd off")
                Log.d(TAG, "[Mute] set_dnd off -> exitCode=$dndCode, out='$dndOut'")

                // 2. Восстановление стандартных потоков
                SoundController.execShizukuCommand("cmd media_session volume --stream 5 --set 5")
                SoundController.execShizukuCommand("settings put system notification_vibration_intensity 2")
                SoundController.execShizukuCommand("settings put global zen_mode 0")
                SoundController.execShizukuCommand("settings put global heads_up_notifications_enabled 1")
            }

            val (_, currentZen) = SoundController.execShizukuCommand("settings get global zen_mode")
            Log.i(TAG, "[Mute Verification] System zen_mode=$currentZen")

            isMuted = muted
            onMuteChangedListener?.invoke(muted)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "[Mute] Ошибка выполнения команд: ${e.message}", e)
            false
        }
    }
}
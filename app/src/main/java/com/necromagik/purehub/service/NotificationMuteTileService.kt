package com.necromagik.purehub.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.necromagik.purehub.R

class NotificationMuteTileService : TileService() {

    companion object {
        private const val TAG = "PureHUB-NotifTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "[Lifecycle] onStartListening: currentMuted=${NotificationMuteController.isMuted}")
        NotificationMuteController.onMuteChangedListener = {
            updateTileState()
        }
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "[Lifecycle] onStopListening")
    }

    override fun onClick() {
        super.onClick()
        val nextState = !NotificationMuteController.isMuted
        Log.i(TAG, "[Click] Нажата плитка глушения. Переключение в: $nextState")
        NotificationMuteController.setNotificationsMuted(nextState)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: run {
            Log.w(TAG, "[UI] updateTileState: qsTile == null")
            return
        }
        val muted = NotificationMuteController.isMuted
        tile.state = if (muted) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (muted) "Баннеры скрыты" else "Уведомления"
        tile.subtitle = if (muted) "Без звука и окон" else "Стандартно"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification_mute)
        tile.updateTile()
        Log.d(TAG, "[UI] Тайл обновлен: state=${tile.state}, label=${tile.label}")
    }
}
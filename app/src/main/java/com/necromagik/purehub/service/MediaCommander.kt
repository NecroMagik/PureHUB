package com.necromagik.purehub.service

import android.util.Log

private const val TAG = "PureHUB-Diag"

object MediaCommander {

    fun nextTrack() {
        Log.i(TAG, "[MediaCommander] Dispatch KEYCODE_MEDIA_NEXT (87)")
        dispatchMediaKey(87)
    }

    fun previousTrack() {
        Log.i(TAG, "[MediaCommander] Dispatch KEYCODE_MEDIA_PREVIOUS (88)")
        dispatchMediaKey(88)
    }

    fun togglePlayPause() {
        Log.i(TAG, "[MediaCommander] Dispatch KEYCODE_MEDIA_PLAY_PAUSE (85)")
        dispatchMediaKey(85)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            val exitCode = process.waitFor()
            Log.d(TAG, "[MediaCommander] 'input keyevent $keyCode' завершен с кодом: $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "[MediaCommander] Ошибка отправки keyevent $keyCode", e)
        }
    }
}
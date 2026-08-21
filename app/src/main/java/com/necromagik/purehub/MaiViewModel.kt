package com.necromagik.purehub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.necromagik.purehub.service.PureHubForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

private const val TAG = "PureHUB-Diag"

data class PureHubUiState(
    val isShizukuRunning: Boolean = false,
    val hasShizukuPermission: Boolean = false,
    val isServiceRunning: Boolean = false
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PureHubUiState())
    val uiState: StateFlow<PureHubUiState> = _uiState.asStateFlow()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[UI] Shizuku permission result (code=$requestCode): granted=$granted")
        _uiState.value = _uiState.value.copy(hasShizukuPermission = granted)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "[UI] Shizuku Binder Received")
        checkShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "[UI] Shizuku Binder Dead")
        _uiState.value = _uiState.value.copy(
            isShizukuRunning = false,
            hasShizukuPermission = false,
            isServiceRunning = false
        )
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        checkShizukuStatus()
    }

    fun checkShizukuStatus() {
        val isRunning = Shizuku.pingBinder()
        val hasPermission = if (isRunning) {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        Log.d(TAG, "[UI] checkShizukuStatus: running=$isRunning, hasPermission=$hasPermission")
        _uiState.value = _uiState.value.copy(
            isShizukuRunning = isRunning,
            hasShizukuPermission = hasPermission
        )
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "[UI] Requesting Shizuku permission")
            Shizuku.requestPermission(1001)
        }
    }

    fun toggleService(context: Context) {
        val nextState = !_uiState.value.isServiceRunning
        Log.i(TAG, "[UI] toggleService -> nextState=$nextState")
        val intent = Intent(context, PureHubForegroundService::class.java)

        if (nextState) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }

        _uiState.value = _uiState.value.copy(isServiceRunning = nextState)
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
package com.necromagik.purehub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necromagik.purehub.service.ProcessManager
import com.necromagik.purehub.service.PureHubForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        _uiState.update { it.copy(hasShizukuPermission = granted) }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "[UI] Shizuku Binder Received")
        checkShizukuStatus()
        ProcessManager.logRunningProcessesSnapshot("Shizuku Ready")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "[UI] Shizuku Binder Dead")
        _uiState.update {
            it.copy(
                isShizukuRunning = false,
                hasShizukuPermission = false,
                isServiceRunning = false
            )
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        PureHubForegroundService.onServiceStateChanged = { running ->
            _uiState.update { it.copy(isServiceRunning = running) }
        }

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
        _uiState.update {
            it.copy(
                isShizukuRunning = isRunning,
                hasShizukuPermission = hasPermission
            )
        }
    }

    /**
     * Синхронизирует положение тумблера с реальными процессами в системе:
     * - Если тумблер должен быть выключен, но процесс :service висит — убивает его.
     * - Если процесс :service реально работает (или несколько) — оставляет один последний и включает тумблер.
     */
    fun syncServiceStateWithReality(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val isTargetEnabled = ProcessManager.isMusicTargetEnabled(context)
            val alivePids = ProcessManager.getMusicServicePids()
            val isProcessAlive = alivePids.isNotEmpty()

            Log.i(TAG, "[Sync] targetEnabled=$isTargetEnabled, alivePids=$alivePids, fgRunning=${PureHubForegroundService.isRunning}")

            val finalRunningState = when {
                !isTargetEnabled && isProcessAlive -> {
                    Log.w(TAG, "[Sync] Обнаружены зомби при выключенном тумблере! Зачистка...")
                    ProcessManager.forceStopMusicProcessesOnly(context)
                    false
                }
                isTargetEnabled && isProcessAlive -> {
                    ProcessManager.deduplicateMusicServiceProcesses()
                    true
                }
                isTargetEnabled && !isProcessAlive -> {
                    Log.w(TAG, "[Sync] Тумблер включен, но процесс :service упал. Перезапуск...")
                    val intent = Intent(context, PureHubForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    true
                }
                else -> false
            }

            _uiState.update { it.copy(isServiceRunning = finalRunningState) }
        }
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "[UI] Requesting Shizuku permission")
            Shizuku.requestPermission(1001)
        }
    }

    fun toggleService(context: Context) {
        val nextState = !_uiState.value.isServiceRunning
        ProcessManager.setMusicTargetEnabled(context, nextState)
        _uiState.update { it.copy(isServiceRunning = nextState) }

        viewModelScope.launch(Dispatchers.IO) {
            if (nextState) {
                ProcessManager.killOldZombieProcesses()
                val intent = Intent(context, PureHubForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                val intent = Intent(context, PureHubForegroundService::class.java)
                context.stopService(intent)
                ProcessManager.killOldZombieProcesses()
            }
        }
    }

    fun executeForceStop(context: Context) {
        _uiState.update { it.copy(isServiceRunning = false) }
        viewModelScope.launch(Dispatchers.IO) {
            ProcessManager.forceStopMusicProcessesOnly(context)
            ProcessManager.logRunningProcessesSnapshot("After Force Stop Music")
        }
    }

    override fun onCleared() {
        super.onCleared()
        PureHubForegroundService.onServiceStateChanged = null
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
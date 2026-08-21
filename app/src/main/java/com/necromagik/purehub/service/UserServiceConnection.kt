package com.necromagik.purehub.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.necromagik.purehub.BuildConfig
import com.necromagik.purehub.IPureHubService
import rikka.shizuku.Shizuku

private const val TAG = "PureHUB-Diag"

class UserServiceConnection(
    private val onConnected: (IPureHubService) -> Unit,
    private val onDisconnected: () -> Unit
) : ServiceConnection {

    private var service: IPureHubService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, PureHubUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    fun bind() {
        if (service != null) return
        Log.d(TAG, "[UserServiceConnection] Вызов Shizuku.bindUserService")
        Shizuku.bindUserService(userServiceArgs, this)
    }

    fun unbind() {
        if (service != null) {
            Log.d(TAG, "[UserServiceConnection] Вызов Shizuku.unbindUserService")
            Shizuku.unbindUserService(userServiceArgs, this, true)
            service = null
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        Log.i(TAG, "[UserServiceConnection] onServiceConnected: $name")
        if (binder != null && binder.isBinderAlive) {
            val ipc = IPureHubService.Stub.asInterface(binder)
            service = ipc
            onConnected(ipc)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.w(TAG, "[UserServiceConnection] onServiceDisconnected: $name")
        service = null
        onDisconnected()
    }
}
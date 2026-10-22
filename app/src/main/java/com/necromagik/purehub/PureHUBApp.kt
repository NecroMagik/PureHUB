package com.necromagik.purehub

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PureHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.e("PureHUB-ProcWatch", ">>> PureHubApp ONCREATE: PID=${Process.myPid()}, thread=${Thread.currentThread().name} <<<")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }
}
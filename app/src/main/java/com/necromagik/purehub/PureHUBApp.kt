package com.necromagik.purehub

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PureHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }
}
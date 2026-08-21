package com.necromagik.purehub.service

import android.annotation.SuppressLint
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

private const val TAG = "PureHUB-Diag"

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object AudioServiceController {

    private var audioServiceInstance: Any? = null
    private var getStreamVolumeMethod: Method? = null
    private var setStreamVolumeMethod: Method? = null

    init {
        initService()
    }

    private fun initService() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "audio") as? IBinder ?: return

            val stubClass = Class.forName("android.media.IAudioService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            audioServiceInstance = asInterfaceMethod.invoke(null, binder)

            val iAudioServiceClass = Class.forName("android.media.IAudioService")

            for (m in iAudioServiceClass.methods) {
                if (m.name == "getStreamVolume") {
                    getStreamVolumeMethod = m
                } else if (m.name == "setStreamVolume") {
                    setStreamVolumeMethod = m
                }
            }
            Log.d(TAG, "[AudioServiceController] IAudioService успешно инициализирован")
        } catch (e: Exception) {
            Log.e(TAG, "[AudioServiceController] Ошибка инициализации IAudioService", e)
        }
    }

    fun getMusicVolume(): Int {
        val service = audioServiceInstance ?: run {
            initService()
            audioServiceInstance ?: return -1
        }
        val method = getStreamVolumeMethod ?: return -1

        return try {
            val params = method.parameterTypes
            val result = when (params.size) {
                1 -> method.invoke(service, 3)
                2 -> method.invoke(service, 3, "com.android.shell")
                3 -> method.invoke(service, 3, "com.android.shell", null)
                else -> -1
            }
            (result as? Int) ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "[AudioServiceController] Ошибка getMusicVolume", e)
            -1
        }
    }

    fun setMusicVolume(volume: Int) {
        if (volume < 0) return
        val service = audioServiceInstance ?: run {
            initService()
            audioServiceInstance ?: return
        }
        val method = setStreamVolumeMethod ?: return

        try {
            val params = method.parameterTypes
            when (params.size) {
                4 -> method.invoke(service, 3, volume, 0, "com.android.shell")
                5 -> method.invoke(service, 3, volume, 0, "com.android.shell", null)
                else -> {
                    val args = arrayOfNulls<Any>(params.size)
                    args[0] = 3
                    args[1] = volume
                    args[2] = 0
                    if (params.size > 3) args[3] = "com.android.shell"
                    method.invoke(service, *args)
                }
            }
            Log.d(TAG, "[AudioServiceController] Громкость восстановлена на: $volume")
        } catch (e: Exception) {
            Log.e(TAG, "[AudioServiceController] Ошибка setMusicVolume", e)
        }
    }
}
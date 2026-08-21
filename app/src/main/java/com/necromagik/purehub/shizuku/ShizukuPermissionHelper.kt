package com.necromagik.purehub.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuPermissionHelper {

    private const val PERM_VOLUME_LONG_PRESS = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER"

    fun hasVolumeLongPressPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            PERM_VOLUME_LONG_PRESS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun grantPermissionViaShizuku(context: Context, onResult: (Boolean) -> Unit) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            onResult(false)
            return
        }

        Thread {
            try {
                val pkgName = context.packageName
                grantRuntimePermission(pkgName, PERM_VOLUME_LONG_PRESS)
                val success = hasVolumeLongPressPermission(context)
                onResult(success)
            } catch (e: Exception) {
                e.printStackTrace()
                // Альтернативный фолбэк через вызов процесса
                grantViaReflectionProcess(context.packageName)
                onResult(hasVolumeLongPressPermission(context))
            }
        }.start()
    }

    @SuppressLint("PrivateApi")
    private fun grantRuntimePermission(packageName: String, permission: String) {
        // Получаем системный PackageManager Binder через Shizuku wrapper
        val packageManagerBinder = SystemServiceHelper.getSystemService("package")
        val wrappedBinder = ShizukuBinderWrapper(packageManagerBinder)

        val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager\$Stub")
        val asInterfaceMethod = iPackageManagerClass.getMethod("asInterface", IBinder::class.java)
        val packageManager = asInterfaceMethod.invoke(null, wrappedBinder)

        val pmClass = packageManager.javaClass

        // Ищем метод grantRuntimePermission (в зависимости от версии Android: 3 или 4 аргумента)
        for (method in pmClass.methods) {
            if (method.name == "grantRuntimePermission") {
                val types = method.parameterTypes
                when (types.size) {
                    3 -> {
                        // grantRuntimePermission(packageName, permission, userId)
                        method.invoke(packageManager, packageName, permission, 0)
                        return
                    }
                    4 -> {
                        // grantRuntimePermission(packageName, permission, deviceId, userId)
                        method.invoke(packageManager, packageName, permission, "default:0", 0)
                        return
                    }
                }
            }
        }
    }

    private fun grantViaReflectionProcess(packageName: String) {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("pm", "grant", packageName, PERM_VOLUME_LONG_PRESS),
                null,
                null
            ) as java.lang.Process
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
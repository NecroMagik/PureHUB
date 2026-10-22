package com.necromagik.purehub.service

import android.content.Context
import android.content.SharedPreferences

object CaffeinePreferences {
    private const val PREFS_NAME = "purehub_caffeine_prefs"
    private const val KEY_TIMERS = "caffeine_timer_steps"

    val DEFAULT_STEPS = listOf(3, 5, 10, 30)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTimerSteps(context: Context): List<Int> {
        val raw = getPrefs(context).getString(KEY_TIMERS, null) ?: return DEFAULT_STEPS
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .ifEmpty { DEFAULT_STEPS }
    }

    fun saveTimerSteps(context: Context, steps: List<Int>) {
        val filtered = steps.filter { it > 0 }
        val serialized = (if (filtered.isEmpty()) DEFAULT_STEPS else filtered).joinToString(",")
        getPrefs(context).edit().putString(KEY_TIMERS, serialized).apply()
    }
}
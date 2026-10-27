package com.necromagik.purehub.widget

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

enum class BigFolderStyle(val title: String, val itemsPerPage: Int) {
    GRID_3X3("Сетка 3×3 (9 иконок)", 9),
    GRID_2X2("Крупная 2×2 (4 иконки)", 4),
    CIRCULAR("Круговая орбита (7 иконок)", 7),
    DIAMOND("Ромб / Крест (5 иконок)", 5),
    HERO("Фокус: 1 большая + 4 (5 иконок)", 5)
}

data class FolderConfig(
    val title: String = "Pure Папка",
    val showTitle: Boolean = false,
    val packages: List<String> = emptyList(),
    val style2x2: BigFolderStyle = BigFolderStyle.GRID_3X3,
    val bgAlpha: Int = 205,                 // 0..255
    val bgColorHex: Int = 0x1E1E1E,         // Цвет фона без альфы
    val cornerRadiusDp: Int = 26,           // Радиус углов папки
    val iconPaddingDp: Int = 4,             // Отступ иконок
    val currentPage: Int = 0,               // Индекс текущей открытой вкладки
    // Новые параметры визуальной кастомизации в тему системы:
    val useSystemMonet: Boolean = false,    // Брать цвет фона и акцента из темы системы (Material You)
    val accentColorHex: Int = 0x00E676,     // Цвет активной капсулы точек и акцентов
    val borderAlpha: Int = 90,              // Яркость стеклянной обводки (0..255)
    val showPopupLabels: Boolean = true,    // Показывать названия приложений в открытой папке
    val popupHorizontalSwipe: Boolean = true // Горизонтальный (true) или вертикальный (false) свайп
)

object FolderWidgetPrefs {
    private const val PREFS_NAME = "purehub_folder_widgets"

    fun saveConfig(context: Context, widgetId: Int, config: FolderConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("title_$widgetId", config.title)
            putBoolean("show_title_$widgetId", config.showTitle)
            putString("pkgs_$widgetId", config.packages.joinToString(","))
            putString("style_$widgetId", config.style2x2.name)
            putInt("alpha_$widgetId", config.bgAlpha)
            putInt("color_$widgetId", config.bgColorHex)
            putInt("radius_$widgetId", config.cornerRadiusDp)
            putInt("padding_$widgetId", config.iconPaddingDp)
            putInt("page_$widgetId", config.currentPage)
            putBoolean("monet_$widgetId", config.useSystemMonet)
            putInt("accent_$widgetId", config.accentColorHex)
            putInt("border_alpha_$widgetId", config.borderAlpha)
            putBoolean("popup_labels_$widgetId", config.showPopupLabels)
            putBoolean("popup_horiz_$widgetId", config.popupHorizontalSwipe)
            apply()
        }
    }

    fun loadConfig(context: Context, widgetId: Int): FolderConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawPkgs = prefs.getString("pkgs_$widgetId", "") ?: ""
        val pkgs = if (rawPkgs.isBlank()) emptyList() else rawPkgs.split(",").filter { it.isNotBlank() }
        val styleName = prefs.getString("style_$widgetId", BigFolderStyle.GRID_3X3.name) ?: BigFolderStyle.GRID_3X3.name
        val style = runCatching { BigFolderStyle.valueOf(styleName) }.getOrDefault(BigFolderStyle.GRID_3X3)

        return FolderConfig(
            title = prefs.getString("title_$widgetId", "Pure Папка") ?: "Pure Папка",
            showTitle = prefs.getBoolean("show_title_$widgetId", false),
            packages = pkgs,
            style2x2 = style,
            bgAlpha = prefs.getInt("alpha_$widgetId", 205),
            bgColorHex = prefs.getInt("color_$widgetId", 0x1E1E1E),
            cornerRadiusDp = prefs.getInt("radius_$widgetId", 26),
            iconPaddingDp = prefs.getInt("padding_$widgetId", 4),
            currentPage = prefs.getInt("page_$widgetId", 0),
            useSystemMonet = prefs.getBoolean("monet_$widgetId", false),
            accentColorHex = prefs.getInt("accent_$widgetId", 0x00E676),
            borderAlpha = prefs.getInt("border_alpha_$widgetId", 90),
            showPopupLabels = prefs.getBoolean("popup_labels_$widgetId", true),
            popupHorizontalSwipe = prefs.getBoolean("popup_horiz_$widgetId", true)
        )
    }

    fun updatePackagesOrder(context: Context, widgetId: Int, newPackages: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("pkgs_$widgetId", newPackages.joinToString(","))
            .apply()
    }

    fun setPage(context: Context, widgetId: Int, page: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("page_$widgetId", page)
            .apply()
    }

    fun deleteConfig(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            remove("title_$widgetId")
            remove("show_title_$widgetId")
            remove("pkgs_$widgetId")
            remove("style_$widgetId")
            remove("alpha_$widgetId")
            remove("color_$widgetId")
            remove("radius_$widgetId")
            remove("padding_$widgetId")
            remove("page_$widgetId")
            remove("monet_$widgetId")
            remove("accent_$widgetId")
            remove("border_alpha_$widgetId")
            remove("popup_labels_$widgetId")
            remove("popup_horiz_$widgetId")
            apply()
        }
    }

    /**
     * Возвращает актуальный цвет фона папки (с учетом системной палитры Monet Android 12+)
     */
    fun resolveBgColorHex(context: Context, config: FolderConfig): Int {
        if (config.useSystemMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return runCatching {
                ContextCompat.getColor(context, android.R.color.system_neutral1_900) and 0xFFFFFF
            }.getOrDefault(config.bgColorHex)
        }
        return config.bgColorHex
    }

    /**
     * Возвращает актуальный акцентный цвет капсулы и выделений (с учетом темы системы)
     */
    fun resolveAccentColorHex(context: Context, config: FolderConfig): Int {
        if (config.useSystemMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return runCatching {
                ContextCompat.getColor(context, android.R.color.system_accent1_300) and 0xFFFFFF
            }.getOrDefault(config.accentColorHex)
        }
        return config.accentColorHex
    }
}
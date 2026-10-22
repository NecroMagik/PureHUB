package com.necromagik.purehub.widget

import android.content.Context

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
    val bgAlpha: Int = 200,          // 0..255
    val bgColorHex: Int = 0x1E1E1E,  // Цвет фона без альфы
    val cornerRadiusDp: Int = 26,    // Радиус углов папки
    val iconPaddingDp: Int = 6       // Внутренний отступ иконок (масштаб)
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
            bgAlpha = prefs.getInt("alpha_$widgetId", 200),
            bgColorHex = prefs.getInt("color_$widgetId", 0x1E1E1E),
            cornerRadiusDp = prefs.getInt("radius_$widgetId", 26),
            iconPaddingDp = prefs.getInt("padding_$widgetId", 6)
        )
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
            apply()
        }
    }
}
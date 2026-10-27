package com.necromagik.purehub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.necromagik.purehub.R

class PureFolderWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_LAUNCH_APP = "com.necromagik.purehub.widget.ACTION_LAUNCH_APP"
        const val ACTION_NEXT_PAGE = "com.necromagik.purehub.widget.ACTION_NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.necromagik.purehub.widget.ACTION_PREV_PAGE"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        // =========================================================================
        // ТОЧНАЯ КАЛИБРОВКА ВЫСОТЫ СЛАЙДОВ И ОТСТУПА НИЖНЕЙ ГРАНИЦЫ ВКЛАДКИ:
        //
        // 1. Высота одного слайда (в dp):
        //    Синхронизирована между 1x2 и 2x2 (150 dp), чтобы ряды стояли на одном уровне!
        //    - Если в 1x2 снизу вдруг покажется край 4-й иконки -> чуть увеличь MIN_SLIDE_HEIGHT_1X2_DP (например, 156..162).
        private const val MIN_SLIDE_HEIGHT_1X2_DP = 200
        private const val MIN_SLIDE_HEIGHT_2X2_DP = 150 // Твоя идеальная настройка для 2x2
        private const val MIN_SLIDE_HEIGHT_2X1_DP = 64

        // 2. ОТСТУП НИЖНЕЙ И ВЕРХНЕЙ ГРАНИЦЫ ВКЛАДКИ (специально для вертикальной папки 1x2):
        //    - SLIDE_1X2_BOTTOM_PADDING_DP: поджимает нижнюю границу слайда 1x2 снизу вверх
        //      (увеличь до 10..16, чтобы поднять нижнюю иконку выше от бара с точками и сблизить тройку иконок).
        //    - SLIDE_1X2_TOP_PADDING_DP: опускает верхнюю границу слайда 1x2 сверху вниз.
        private const val SLIDE_1X2_TOP_PADDING_DP = 4
        private const val SLIDE_1X2_BOTTOM_PADDING_DP = 10

        // 3. Баланс внутренних отступов самих иконок (0 = максимальный размер иконки без сжатия):
        private const val EXTRA_PAD_2X2_DP = 0   // Твоя настройка для 2x2
        private const val EXTRA_PAD_1X2_DP = 0  // Убирает базовый ползунок до 0dp, делая иконки в 1x2 крупными!
        private const val EXTRA_PAD_2X1_DP = 2
        // =========================================================================

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PureFolderWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

            val cols = if (minWidth >= 115) 2 else 1
            val rows = if (minHeight >= 115) 2 else 1

            val config = FolderWidgetPrefs.loadConfig(context, appWidgetId)
            val rootViews = RemoteViews(context.packageName, R.layout.widget_folder_root)

            // 1. Отрисовка кастомной подложки
            val actualHeightDp = maxHeight.coerceAtLeast(minHeight)
            val widthPx = dpToPx(context, minWidth.coerceAtLeast(60))
            val heightPx = dpToPx(context, actualHeightDp.coerceAtLeast(60))
            val radiusPx = dpToPx(context, config.cornerRadiusDp).toFloat()
            val resolvedBgHex = FolderWidgetPrefs.resolveBgColorHex(context, config)
            val resolvedAccentHex = FolderWidgetPrefs.resolveAccentColorHex(context, config)
            val bgBitmap = createRoundedBgBitmap(
                w = widthPx,
                h = heightPx,
                radius = radiusPx,
                colorHex = resolvedBgHex,
                alpha = config.bgAlpha,
                borderAlpha = config.borderAlpha
            )
            rootViews.setImageViewBitmap(R.id.folder_bg_image, bgBitmap)

            // 2. PendingIntent для открытия всплывающей папки
            val popupIntent = Intent(context, FolderPopupActivity::class.java).apply {
                data = Uri.parse("purehub://folder_popup/$appWidgetId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val popupPi = PendingIntent.getActivity(
                context,
                appWidgetId + 5000,
                popupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // PendingIntent для шестеренки
            val configIntent = Intent(context, FolderConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                data = Uri.parse("purehub://folder_config/$appWidgetId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val configPi = PendingIntent.getActivity(
                context,
                appWidgetId + 7000,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            rootViews.setOnClickPendingIntent(R.id.folder_root, popupPi)
            rootViews.setOnClickPendingIntent(R.id.folder_bg_image, popupPi)
            rootViews.setOnClickPendingIntent(R.id.folder_title_text, popupPi)
            rootViews.setOnClickPendingIntent(R.id.btn_folder_settings, configPi)

            // 3. Если папка пустая
            if (config.packages.isEmpty()) {
                rootViews.setViewVisibility(R.id.folder_empty_hint, View.VISIBLE)
                rootViews.setViewVisibility(R.id.folder_1x1_preview, View.GONE)
                rootViews.setViewVisibility(R.id.folder_content_box, View.GONE)
                rootViews.setOnClickPendingIntent(R.id.folder_empty_hint, configPi)
                appWidgetManager.updateAppWidget(appWidgetId, rootViews)
                return
            } else {
                rootViews.setViewVisibility(R.id.folder_empty_hint, View.GONE)
            }

            // 4. Режим 1x1
            if (cols == 1 && rows == 1) {
                rootViews.setViewVisibility(R.id.folder_1x1_preview, View.VISIBLE)
                rootViews.setViewVisibility(R.id.folder_content_box, View.GONE)

                val previewBmp = render1x1PreviewBitmap(context, config)
                rootViews.setImageViewBitmap(R.id.folder_1x1_preview, previewBmp)
                rootViews.setOnClickPendingIntent(R.id.folder_1x1_preview, popupPi)

                appWidgetManager.updateAppWidget(appWidgetId, rootViews)
                return
            }

            // 5. Режимы 2x1, 1x2 и 2x2
            rootViews.setViewVisibility(R.id.folder_1x1_preview, View.GONE)
            rootViews.setViewVisibility(R.id.folder_content_box, View.VISIBLE)

            val hasTitle = config.showTitle && config.title.isNotBlank()
            if (hasTitle) {
                rootViews.setViewVisibility(R.id.folder_title_text, View.VISIBLE)
                rootViews.setTextViewText(R.id.folder_title_text, config.title)
            } else {
                rootViews.setViewVisibility(R.id.folder_title_text, View.GONE)
            }

            val itemsPerPage = when {
                cols == 2 && rows == 2 -> config.style2x2.itemsPerPage
                else -> 3 // В 1x2 и 2x1 всегда ровно по 3 иконки на вкладку
            }

            val rawPages = config.packages.chunked(itemsPerPage)
            val totalPages = rawPages.size.coerceAtLeast(1)
            val safePage = config.currentPage.coerceIn(0, totalPages - 1)

            val orderedPages = if (totalPages > 1 && safePage > 0) {
                rawPages.drop(safePage) + rawPages.take(safePage)
            } else {
                rawPages
            }

            val titleOverhead = if (hasTitle) 18 else 0
            val exactSlideHeightDp = when {
                cols == 1 && rows == 2 -> (actualHeightDp - 36 - titleOverhead).coerceAtLeast(MIN_SLIDE_HEIGHT_1X2_DP)
                cols == 2 && rows == 2 -> (actualHeightDp - 36 - titleOverhead).coerceAtLeast(MIN_SLIDE_HEIGHT_2X2_DP)
                else -> (actualHeightDp - 32 - titleOverhead).coerceAtLeast(MIN_SLIDE_HEIGHT_2X1_DP)
            }

            val clickTemplateIntent = Intent(context, PureFolderWidgetProvider::class.java).apply {
                action = ACTION_LAUNCH_APP
                data = Uri.parse("purehub://launch_template/$appWidgetId")
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val clickTemplatePi = PendingIntent.getBroadcast(
                context,
                appWidgetId + 9000,
                clickTemplateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            rootViews.setPendingIntentTemplate(R.id.folder_vertical_pager, clickTemplatePi)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val collectionBuilder = RemoteViews.RemoteCollectionItems.Builder()
                orderedPages.forEachIndexed { idx, pagePkgs ->
                    val slideView = buildSlideRemoteViews(
                        context = context,
                        cols = cols,
                        rows = rows,
                        style2x2 = config.style2x2,
                        pagePackages = pagePkgs,
                        basePaddingDp = config.iconPaddingDp,
                        slideHeightDp = exactSlideHeightDp
                    )
                    collectionBuilder.addItem(idx.toLong(), slideView)
                }
                rootViews.setRemoteAdapter(R.id.folder_vertical_pager, collectionBuilder.build())
            }

            // 6. Нижний индикатор точек OxygenOS
            if (totalPages > 1) {
                rootViews.setViewVisibility(R.id.folder_pager_touch_bar, View.VISIBLE)
                val dotsBmp = createOxygenDotsBitmap(
                    totalPages = totalPages,
                    activeIndex = safePage,
                    compactMode = (cols == 1),
                    accentColorHex = resolvedAccentHex
                )
                rootViews.setImageViewBitmap(R.id.slide_dots_indicator, dotsBmp)

                val prevIntent = Intent(context, PureFolderWidgetProvider::class.java).apply {
                    action = ACTION_PREV_PAGE
                    data = Uri.parse("purehub://prev_tab/$appWidgetId")
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                val prevPi = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 10 + 1,
                    prevIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val nextIntent = Intent(context, PureFolderWidgetProvider::class.java).apply {
                    action = ACTION_NEXT_PAGE
                    data = Uri.parse("purehub://next_tab/$appWidgetId")
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                val nextPi = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 10 + 2,
                    nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                rootViews.setOnClickPendingIntent(R.id.btn_prev_tab, prevPi)
                rootViews.setOnClickPendingIntent(R.id.slide_dots_indicator, nextPi)
                rootViews.setOnClickPendingIntent(R.id.btn_next_tab, nextPi)
            } else {
                rootViews.setViewVisibility(R.id.folder_pager_touch_bar, View.INVISIBLE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, rootViews)
        }

        private fun buildSlideRemoteViews(
            context: Context,
            cols: Int,
            rows: Int,
            style2x2: BigFolderStyle,
            pagePackages: List<String>,
            basePaddingDp: Int,
            slideHeightDp: Int
        ): RemoteViews {
            val layoutId = when {
                cols == 2 && rows == 1 -> R.layout.item_slide_2x1
                cols == 1 && rows == 2 -> R.layout.item_slide_1x2
                else -> when (style2x2) {
                    BigFolderStyle.GRID_3X3 -> R.layout.item_slide_2x2_grid3x3
                    BigFolderStyle.GRID_2X2 -> R.layout.item_slide_2x2_grid2x2
                    BigFolderStyle.CIRCULAR -> R.layout.item_slide_2x2_circular
                    BigFolderStyle.DIAMOND -> R.layout.item_slide_2x2_diamond
                    BigFolderStyle.HERO -> R.layout.item_slide_2x2_hero
                }
            }

            val slideViews = RemoteViews(context.packageName, layoutId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                slideViews.setViewLayoutHeight(
                    R.id.slide_root_container,
                    slideHeightDp.toFloat(),
                    TypedValue.COMPLEX_UNIT_DIP
                )
            }

            // Применяем точечный отступ верхней и нижней границы вкладки для 1x2
            if (cols == 1 && rows == 2) {
                slideViews.setViewPadding(
                    R.id.slide_root_container,
                    0,
                    dpToPx(context, SLIDE_1X2_TOP_PADDING_DP),
                    0,
                    dpToPx(context, SLIDE_1X2_BOTTOM_PADDING_DP)
                )
            }

            val slotIds = intArrayOf(
                R.id.folder_slot_1, R.id.folder_slot_2, R.id.folder_slot_3,
                R.id.folder_slot_4, R.id.folder_slot_5, R.id.folder_slot_6,
                R.id.folder_slot_7, R.id.folder_slot_8, R.id.folder_slot_9
            )

            val extraPadDp = when {
                cols == 1 && rows == 2 -> EXTRA_PAD_1X2_DP
                cols == 2 && rows == 1 -> EXTRA_PAD_2X1_DP
                else -> EXTRA_PAD_2X2_DP
            }
            // Разрешаем 0 dp отступа, чтобы иконки в 1x2 не зажимались!
            val effectivePaddingDp = (basePaddingDp + extraPadDp).coerceIn(0, 18)
            val padPx = dpToPx(context, effectivePaddingDp)

            // Для 1x2 убрали принудительный боковой зажим (+4), который делал иконки мелкими:
            val horizPadPx = if (cols == 1 && rows == 2) dpToPx(context, effectivePaddingDp) else padPx
            val vertPadPx = if (cols == 1 && rows == 2) dpToPx(context, effectivePaddingDp + 1) else padPx

            val pm = context.packageManager

            for (i in slotIds.indices) {
                val slotId = slotIds[i]
                if (i < pagePackages.size) {
                    val pkg = pagePackages[i]
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        slideViews.setImageViewBitmap(slotId, drawableToBitmap(icon, 192))
                        slideViews.setViewPadding(slotId, horizPadPx, vertPadPx, horizPadPx, vertPadPx)
                        slideViews.setViewVisibility(slotId, View.VISIBLE)

                        val fillInIntent = Intent().apply {
                            putExtra(EXTRA_PACKAGE_NAME, pkg)
                        }
                        slideViews.setOnClickFillInIntent(slotId, fillInIntent)
                    } catch (_: Exception) {
                        slideViews.setViewVisibility(slotId, View.INVISIBLE)
                    }
                } else {
                    slideViews.setViewVisibility(slotId, View.INVISIBLE)
                }
            }

            val emptySpaceFillIntent = Intent().apply {
                putExtra(EXTRA_PACKAGE_NAME, "")
            }
            slideViews.setOnClickFillInIntent(R.id.slide_root_container, emptySpaceFillIntent)

            return slideViews
        }

        private fun createOxygenDotsBitmap(
            totalPages: Int,
            activeIndex: Int,
            compactMode: Boolean = false,
            accentColorHex: Int = 0x00E676
        ): Bitmap {
            val visibleDots = if (compactMode) totalPages.coerceAtMost(3) else totalPages.coerceAtMost(7)
            val safeActive = activeIndex % visibleDots

            val dotDiameter = if (compactMode) 12f else 13f
            val activeWidth = if (compactMode) 28f else 34f
            val spacing = if (compactMode) 8f else 10f
            val height = 20

            val totalWidth = ((visibleDots - 1) * dotDiameter + activeWidth + (visibleDots - 1) * spacing + 8f).toInt()
            val bmp = Bitmap.createBitmap(totalWidth.coerceAtLeast(20), height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0xFF shl 24) or (accentColorHex and 0xFFFFFF)
                style = Paint.Style.FILL
            }
            val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(115, 255, 255, 255)
                style = Paint.Style.FILL
            }

            var currentX = 4f
            val centerY = height / 2f
            val radius = dotDiameter / 2f

            for (i in 0 until visibleDots) {
                if (i == safeActive) {
                    val rect = RectF(currentX, centerY - radius, currentX + activeWidth, centerY + radius)
                    canvas.drawRoundRect(rect, radius, radius, activePaint)
                    currentX += activeWidth + spacing
                } else {
                    canvas.drawCircle(currentX + radius, centerY, radius, inactivePaint)
                    currentX += dotDiameter + spacing
                }
            }
            return bmp
        }

        private fun render1x1PreviewBitmap(context: Context, config: FolderConfig): Bitmap {
            val size = 220
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val pm = context.packageManager
            val previewPkgs = config.packages.take(4)

            val iconSize = 86
            val positions = arrayOf(
                PointF(18f, 18f),
                PointF(116f, 18f),
                PointF(18f, 116f),
                PointF(116f, 116f)
            )

            for (i in previewPkgs.indices) {
                try {
                    val icon = pm.getApplicationIcon(previewPkgs[i])
                    val iconBmp = drawableToBitmap(icon, iconSize)
                    canvas.drawBitmap(iconBmp, positions[i].x, positions[i].y, null)
                } catch (_: Exception) {}
            }
            return bmp
        }

        private fun createRoundedBgBitmap(
            w: Int,
            h: Int,
            radius: Float,
            colorHex: Int,
            alpha: Int,
            borderAlpha: Int = 90
        ): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (alpha.coerceIn(0, 255) shl 24) or (colorHex and 0xFFFFFF)
                style = Paint.Style.FILL
            }
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)

            if (borderAlpha > 0) {
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(borderAlpha.coerceIn(0, 255), 255, 255, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                }
                rect.inset(1.5f, 1.5f)
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }
            return bmp
        }

        fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            return bitmap
        }

        private fun dpToPx(context: Context, dp: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LAUNCH_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

                if (!pkg.isNullOrBlank()) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    }
                } else if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val popupIntent = Intent(context, FolderPopupActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(EXTRA_WIDGET_ID, widgetId)
                    }
                    context.startActivity(popupIntent)
                }
            }
            ACTION_NEXT_PAGE, ACTION_PREV_PAGE -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

                val appWidgetManager = AppWidgetManager.getInstance(context)
                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val cols = if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110) >= 115) 2 else 1
                val rows = if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) >= 115) 2 else 1

                val config = FolderWidgetPrefs.loadConfig(context, widgetId)
                val itemsPerPage = if (cols == 2 && rows == 2) config.style2x2.itemsPerPage else 3
                val totalPages = ((config.packages.size + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)

                val delta = if (intent.action == ACTION_NEXT_PAGE) 1 else -1
                val nextPage = (config.currentPage + delta + totalPages) % totalPages
                FolderWidgetPrefs.setPage(context, widgetId, nextPage)
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            FolderWidgetPrefs.deleteConfig(context, id)
        }
    }
}
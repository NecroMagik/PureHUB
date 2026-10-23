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
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        // =========================================================================
        // БЛОК ТОНКОЙ КАЛИБРОВКИ СЕТКИ И ВЫСОТЫ СЛАЙДА (ДЛЯ ПАПКИ 2x2 И 2x1):
        //
        // 1. SLIDE_HEIGHT_CORRECTION_DP:
        //    Отвечает за то, чтобы снизу НЕ ТОРЧАЛА вторая девятка приложений!
        //    - Если снизу всё ещё видна верхушка второго слайда -> УВЕЛИЧЬ это число (например, +12, +18, +24).
        //    - Если первый слайд обрезается снизу -> УМЕНЬШИ это число (например, 0 или -8).
        private const val SLIDE_HEIGHT_CORRECTION_DP = 14

        // 2. EXTRA_ICON_SHRINK_DP:
        //    Дополнительное уменьшение иконок внутри ячеек сетки (плюсуется к ползунку "Отступ иконок" в настройках).
        //    - Увеличь (например, до 4..8), чтобы сделать сами иконки в сетке 3x3 меньше и воздушнее.
        private const val EXTRA_ICON_SHRINK_DP = 3
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
            // В портретном режиме реальная высота виджета лежит в OPTION_APPWIDGET_MAX_HEIGHT!
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

            val cols = if (minWidth >= 115) 2 else 1
            val rows = if (minHeight >= 115) 2 else 1

            val config = FolderWidgetPrefs.loadConfig(context, appWidgetId)
            val rootViews = RemoteViews(context.packageName, R.layout.widget_folder_root)

            // 1. Отрисовка кастомной подложки по реальным пропорциям
            val actualHeightDp = maxHeight.coerceAtLeast(minHeight)
            val widthPx = dpToPx(context, minWidth.coerceAtLeast(60))
            val heightPx = dpToPx(context, actualHeightDp.coerceAtLeast(60))
            val radiusPx = dpToPx(context, config.cornerRadiusDp).toFloat()
            val bgBitmap = createRoundedBgBitmap(widthPx, heightPx, radiusPx, config.bgColorHex, config.bgAlpha)
            rootViews.setImageViewBitmap(R.id.folder_bg_image, bgBitmap)

            // 2. PendingIntent для открытия всплывающей папки (перехватывает клики мимо иконок и по капсуле)
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

            // PendingIntent для шестеренки (открывает настройки папки)
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
            rootViews.setOnClickPendingIntent(R.id.folder_bottom_bar, popupPi)
            rootViews.setOnClickPendingIntent(R.id.slide_dots_indicator, popupPi)
            rootViews.setOnClickPendingIntent(R.id.folder_title_text, popupPi)
            rootViews.setOnClickPendingIntent(R.id.btn_folder_settings, configPi)

            // 3. Если папка пустая — показываем "+ Настроить папку"
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

            // 5. Режимы 2x1, 1x2 и 2x2 с линейным вертикальным свайпом слайдов
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
                else -> 3
            }

            val pages = config.packages.chunked(itemsPerPage)
            val totalPages = pages.size.coerceAtLeast(1)

            // =========================================================================
            // РАСЧЕТ ТОЧНОЙ ВЫСОТЫ ОДНОГО СЛАЙДА:
            // Берем реальную портретную высоту actualHeightDp (MAX_HEIGHT),
            // вычитаем высоту нижнего бара с точками + отступы (40dp) и прибавляем SLIDE_HEIGHT_CORRECTION_DP,
            // чтобы второй слайд гарантированно ушел вниз за границу видимости!
            // =========================================================================
            val chromeOverheadDp = 40 + (if (hasTitle) 18 else 0)
            val exactSlideHeightDp = (actualHeightDp - chromeOverheadDp + SLIDE_HEIGHT_CORRECTION_DP)
                .coerceAtLeast(if (rows == 2) 138 else 58)

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
            rootViews.setPendingIntentTemplate(R.id.folder_linear_pager, clickTemplatePi)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val collectionBuilder = RemoteViews.RemoteCollectionItems.Builder()
                pages.forEachIndexed { pageIndex, pagePkgs ->
                    val slideView = buildSlideRemoteViews(
                        context = context,
                        cols = cols,
                        rows = rows,
                        style2x2 = config.style2x2,
                        pagePackages = pagePkgs,
                        iconPaddingDp = config.iconPaddingDp + EXTRA_ICON_SHRINK_DP,
                        slideHeightDp = exactSlideHeightDp
                    )
                    collectionBuilder.addItem(pageIndex.toLong(), slideView)
                }
                rootViews.setRemoteAdapter(R.id.folder_linear_pager, collectionBuilder.build())
            }

            if (totalPages > 1) {
                rootViews.setViewVisibility(R.id.slide_dots_indicator, View.VISIBLE)
                val dotsBmp = createOxygenDotsBitmap(totalPages, 0)
                rootViews.setImageViewBitmap(R.id.slide_dots_indicator, dotsBmp)
            } else {
                rootViews.setViewVisibility(R.id.slide_dots_indicator, View.INVISIBLE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, rootViews)
        }

        private fun buildSlideRemoteViews(
            context: Context,
            cols: Int,
            rows: Int,
            style2x2: BigFolderStyle,
            pagePackages: List<String>,
            iconPaddingDp: Int,
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

            val slotIds = intArrayOf(
                R.id.folder_slot_1, R.id.folder_slot_2, R.id.folder_slot_3,
                R.id.folder_slot_4, R.id.folder_slot_5, R.id.folder_slot_6,
                R.id.folder_slot_7, R.id.folder_slot_8, R.id.folder_slot_9
            )

            val padPx = dpToPx(context, iconPaddingDp)
            val pm = context.packageManager

            for (i in slotIds.indices) {
                val slotId = slotIds[i]
                if (i < pagePackages.size) {
                    val pkg = pagePackages[i]
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        slideViews.setImageViewBitmap(slotId, drawableToBitmap(icon, 168))
                        slideViews.setViewPadding(slotId, padPx, padPx, padPx, padPx)
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

        private fun createOxygenDotsBitmap(totalPages: Int, activeIndex: Int): Bitmap {
            val dotDiameter = 14f
            val activeWidth = 38f
            val spacing = 12f
            val height = 20

            val totalWidth = ((totalPages - 1) * dotDiameter + activeWidth + (totalPages - 1) * spacing + 8f).toInt()
            val bmp = Bitmap.createBitmap(totalWidth.coerceAtLeast(20), height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E676")
                style = Paint.Style.FILL
            }
            val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(95, 255, 255, 255)
                style = Paint.Style.FILL
            }

            var currentX = 4f
            val centerY = height / 2f
            val radius = dotDiameter / 2f

            for (i in 0 until totalPages) {
                if (i == activeIndex) {
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

        private fun createRoundedBgBitmap(w: Int, h: Int, radius: Float, colorHex: Int, alpha: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (alpha.coerceIn(0, 255) shl 24) or (colorHex and 0xFFFFFF)
                style = Paint.Style.FILL
            }
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.35f).toInt().coerceIn(0, 255), 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            rect.inset(1.5f, 1.5f)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
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
        if (intent.action == ACTION_LAUNCH_APP) {
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
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            FolderWidgetPrefs.deleteConfig(context, id)
        }
    }
}
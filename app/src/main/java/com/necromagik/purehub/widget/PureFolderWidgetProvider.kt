package com.necromagik.purehub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
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

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)

            val cols = if (minWidth >= 115) 2 else 1
            val rows = if (minHeight >= 115) 2 else 1

            val config = FolderWidgetPrefs.loadConfig(context, appWidgetId)
            val rootViews = RemoteViews(context.packageName, R.layout.widget_folder_root)

            // 1. Отрисовка кастомной подложки
            val widthPx = dpToPx(context, minWidth.coerceAtLeast(60))
            val heightPx = dpToPx(context, minHeight.coerceAtLeast(60))
            val radiusPx = dpToPx(context, config.cornerRadiusDp).toFloat()
            val bgBitmap = createRoundedBgBitmap(widthPx, heightPx, radiusPx, config.bgColorHex, config.bgAlpha)
            rootViews.setImageViewBitmap(R.id.folder_bg_image, bgBitmap)

            // PendingIntent для открытия экрана кастомизации этой папки
            val configIntent = Intent(context, FolderConfigActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val configPi = PendingIntent.getActivity(
                context,
                appWidgetId + 7000,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rootViews.setOnClickPendingIntent(R.id.btn_folder_settings, configPi)

            // 2. Если папка пустая — показываем кнопку "+ Настроить папку"
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

            // 3. Режим 1x1: превью 2x2 на одном холсте, клик открывает всплывающее окно папки
            if (cols == 1 && rows == 1) {
                rootViews.setViewVisibility(R.id.folder_1x1_preview, View.VISIBLE)
                rootViews.setViewVisibility(R.id.folder_content_box, View.GONE)

                val previewBmp = render1x1PreviewBitmap(context, config)
                rootViews.setImageViewBitmap(R.id.folder_1x1_preview, previewBmp)

                val popupIntent = Intent(context, FolderPopupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                val popupPi = PendingIntent.getActivity(
                    context,
                    appWidgetId + 5000,
                    popupIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rootViews.setOnClickPendingIntent(R.id.folder_1x1_preview, popupPi)
                appWidgetManager.updateAppWidget(appWidgetId, rootViews)
                return
            }

            // 4. Режимы 2x1, 1x2 и 2x2 с вертикальной прокруткой слайдов
            rootViews.setViewVisibility(R.id.folder_1x1_preview, View.GONE)
            rootViews.setViewVisibility(R.id.folder_content_box, View.VISIBLE)
            rootViews.setViewVisibility(R.id.folder_vertical_pager, View.VISIBLE)
            rootViews.setTextViewText(R.id.folder_title_text, if (config.showTitle) config.title else "")

            val clickTemplateIntent = Intent(context, PureFolderWidgetProvider::class.java).apply {
                action = ACTION_LAUNCH_APP
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
                val itemsPerPage = when {
                    cols == 2 && rows == 2 -> config.style2x2.itemsPerPage
                    else -> 3
                }

                val pages = config.packages.chunked(itemsPerPage)

                pages.forEachIndexed { pageIndex, pagePkgs ->
                    val pageView = buildSlideRemoteViews(
                        context = context,
                        cols = cols,
                        rows = rows,
                        style2x2 = config.style2x2,
                        pagePackages = pagePkgs,
                        iconPaddingDp = config.iconPaddingDp,
                        slideHeightDp = if (rows == 2) 132 else 60
                    )
                    collectionBuilder.addItem(pageIndex.toLong(), pageView)
                }

                rootViews.setRemoteAdapter(R.id.folder_vertical_pager, collectionBuilder.build())
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
                slideViews.setViewLayoutHeight(R.id.slide_root_container, slideHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
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
                        slideViews.setImageViewBitmap(slotId, drawableToBitmap(icon, 144))
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

            return slideViews
        }

        private fun render1x1PreviewBitmap(context: Context, config: FolderConfig): Bitmap {
            val size = 192
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val pm = context.packageManager
            val previewPkgs = config.packages.take(4)

            val iconSize = 68
            val positions = arrayOf(
                PointF(22f, 22f),
                PointF(102f, 22f),
                PointF(22f, 102f),
                PointF(102f, 102f)
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
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            FolderWidgetPrefs.deleteConfig(context, id)
        }
    }
}
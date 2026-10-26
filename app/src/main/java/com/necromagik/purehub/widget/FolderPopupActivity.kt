package com.necromagik.purehub.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necromagik.purehub.ui.theme.LocalPureHubConfig
import com.necromagik.purehub.ui.theme.PureHUBTheme
import kotlin.math.abs

// =========================================================================
// ПЕРЕКЛЮЧАТЕЛЬ РЕЖИМА ПРОКРУТКИ СЛАЙДОВ В ПАПКЕ:
// Поставь true  — для ГОРИЗОНТАЛЬНОГО свайпа влево/вправо (HorizontalPager)
// Поставь false — для ВЕРТИКАЛЬНОГО свайпа вверх/вниз (VerticalPager)
// =========================================================================
private const val IS_HORIZONTAL_SWIPE = true

class FolderPopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderFolderPopup(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFolderPopup(intent)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun renderFolderPopup(currentIntent: Intent?) {
        val widgetId = currentIntent?.getIntExtra(
            PureFolderWidgetProvider.EXTRA_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val config = FolderWidgetPrefs.loadConfig(this, widgetId)

        setContent {
            PureHUBTheme {
                val context = LocalContext.current
                val themeConfig = LocalPureHubConfig.current
                val pm = context.packageManager

                val apps = remember(config.packages) {
                    config.packages.mapNotNull { pkg ->
                        runCatching {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            Triple(
                                pkg,
                                pm.getApplicationLabel(appInfo).toString(),
                                pm.getApplicationIcon(pkg)
                            )
                        }.getOrNull()
                    }
                }

                // Разбиваем приложения по вкладкам (по 9 иконок на слайд 3x3)
                val pages = remember(apps) {
                    if (apps.isEmpty()) listOf(emptyList()) else apps.chunked(9)
                }
                val pagerState = rememberPagerState(pageCount = { pages.size })

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { finish() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .wrapContentHeight()
                            .clip(RoundedCornerShape(config.cornerRadiusDp.dp))
                            .background(
                                Color(0xFF000000 or config.bgColorHex.toLong()).copy(
                                    alpha = (config.bgAlpha / 255f).coerceIn(0.65f, 0.98f)
                                )
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(config.cornerRadiusDp.dp)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {}
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Шапка открытой папки
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = config.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            TextButton(
                                onClick = {
                                    val editIntent = Intent(context, FolderConfigActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                                    }
                                    context.startActivity(editIntent)
                                    finish()
                                }
                            ) {
                                Text("⚙\uFE0F Изменить", color = themeConfig.accentColor, fontSize = 12.sp)
                            }
                        }

                        // Свайп-область слайдов (горизонтальная или вертикальная в зависимости от константы IS_HORIZONTAL_SWIPE)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(265.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (IS_HORIZONTAL_SWIPE) {
                                // ГОРИЗОНТАЛЬНАЯ ПРОКРУТКА (влево / вправо)
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { pageIdx ->
                                    PopupSlideGrid(
                                        slideApps = pages[pageIdx],
                                        onLaunchApp = { pkg ->
                                            pm.getLaunchIntentForPackage(pkg)?.let {
                                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(it)
                                            }
                                            finish()
                                        }
                                    )
                                }
                            } else {
                                // ВЕРТИКАЛЬНАЯ ПРОКРУТКА (вверх / вниз)
                                VerticalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { pageIdx ->
                                    PopupSlideGrid(
                                        slideApps = pages[pageIdx],
                                        onLaunchApp = { pkg ->
                                            pm.getLaunchIntentForPackage(pkg)?.let {
                                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(it)
                                            }
                                            finish()
                                        }
                                    )
                                }
                            }
                        }

                        // Динамические перетекающие точки в стиле OxygenOS внизу папки
                        if (pages.size > 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            PopupOxygenMorphingDots(
                                pagerState = pagerState,
                                pageCount = pages.size,
                                activeColor = themeConfig.accentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupSlideGrid(
    slideApps: List<Triple<String, String, android.graphics.drawable.Drawable>>,
    onLaunchApp: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(slideApps, key = { it.first }) { (pkg, label, drawable) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onLaunchApp(pkg) }
                    .padding(6.dp)
            ) {
                val bmp = remember(pkg) {
                    PureFolderWidgetProvider.drawableToBitmap(drawable, 144).asImageBitmap()
                }
                Image(
                    bitmap = bmp,
                    contentDescription = label,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PopupOxygenMorphingDots(
    pagerState: PagerState,
    pageCount: Int,
    activeColor: Color
) {
    val currentPage = pagerState.currentPage
    val offsetFraction = pagerState.currentPageOffsetFraction

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(10.dp)
    ) {
        for (i in 0 until pageCount) {
            val distance = abs((currentPage + offsetFraction) - i).coerceIn(0f, 1f)
            val activeFraction = 1f - distance
            val widthDp = 7.dp + (16.dp * activeFraction)
            val dotColor = lerp(
                Color.White.copy(alpha = 0.3f),
                activeColor,
                activeFraction
            )

            Box(
                modifier = Modifier
                    .width(widthDp)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
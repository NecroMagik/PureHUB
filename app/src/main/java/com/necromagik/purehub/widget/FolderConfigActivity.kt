package com.necromagik.purehub.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.necromagik.purehub.ui.components.PureSwitch
import com.necromagik.purehub.ui.theme.LocalPureHubConfig
import com.necromagik.purehub.ui.theme.PureHUBTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import kotlin.math.hypot

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

class FolderConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val initialConfig = FolderWidgetPrefs.loadConfig(this, appWidgetId)

        setContent {
            PureHUBTheme {
                FolderConfigScreen(
                    initialConfig = initialConfig,
                    onSave = { updatedConfig ->
                        FolderWidgetPrefs.saveConfig(this, appWidgetId, updatedConfig)
                        val appWidgetManager = AppWidgetManager.getInstance(this)
                        PureFolderWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

                        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                )
            }
        }
    }
}

/**
 * Компаратор сортировки: сначала кириллица (А-Я), затем латиница (A-Z), затем прочие символы
 */
private fun compareAppLabelsCyrillicFirst(a: String, b: String): Int {
    val cleanA = a.trim()
    val cleanB = b.trim()
    val firstA = cleanA.firstOrNull()?.uppercaseChar() ?: ' '
    val firstB = cleanB.firstOrNull()?.uppercaseChar() ?: ' '

    fun charGroup(c: Char): Int = when {
        c in 'А'..'Я' || c == 'Ё' -> 0 // Кириллица первой
        c in 'A'..'Z' -> 1             // Латиница второй
        else -> 2                      // Цифры и символы
    }

    val groupA = charGroup(firstA)
    val groupB = charGroup(firstB)

    if (groupA != groupB) {
        return groupA.compareTo(groupB)
    }

    val collator = Collator.getInstance(Locale("ru", "RU"))
    return collator.compare(cleanA, cleanB)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FolderConfigScreen(
    initialConfig: FolderConfig,
    onSave: (FolderConfig) -> Unit
) {
    val context = LocalContext.current
    val themeConfig = LocalPureHubConfig.current

    var title by remember { mutableStateOf(initialConfig.title) }
    var showTitle by remember { mutableStateOf(initialConfig.showTitle) }
    var selectedPackages by remember { mutableStateOf(initialConfig.packages) }
    var style2x2 by remember { mutableStateOf(initialConfig.style2x2) }
    var bgAlpha by remember { mutableFloatStateOf(initialConfig.bgAlpha.toFloat()) }
    var bgColorHex by remember { mutableIntStateOf(initialConfig.bgColorHex) }
    var cornerRadiusDp by remember { mutableFloatStateOf(initialConfig.cornerRadiusDp.toFloat()) }
    var iconPaddingDp by remember { mutableFloatStateOf(initialConfig.iconPaddingDp.toFloat()) }

    var showAppPickerSheet by remember { mutableStateOf(false) }

    val allApps by produceState(initialValue = emptyList<InstalledAppItem>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(mainIntent, 0)
                .mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    val label = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    InstalledAppItem(pkg, label, icon)
                }
                .distinctBy { it.packageName }
                .sortedWith { a, b -> compareAppLabelsCyrillicFirst(a.label, b.label) }
        }
    }

    val appsByPkg = remember(allApps) {
        allApps.associateBy { it.packageName }
    }

    val selectedAppItems = remember(selectedPackages, appsByPkg) {
        selectedPackages.mapNotNull { appsByPkg[it] }
    }

    val colorPresets = listOf(
        0x1E1E1E to "Графит",
        0x000000 to "Черный OLED",
        0x0D291E to "Изумруд",
        0x1A233A to "Полночь",
        0x2D1B2E to "Аметист"
    )

    if (showAppPickerSheet) {
        AppPickerBottomSheet(
            allApps = allApps,
            selectedPackages = selectedPackages,
            accentColor = themeConfig.accentColor,
            onToggleApp = { pkg ->
                selectedPackages = if (selectedPackages.contains(pkg)) {
                    selectedPackages - pkg
                } else {
                    selectedPackages + pkg
                }
            },
            onDismiss = { showAppPickerSheet = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Button(
                    onClick = {
                        onSave(
                            FolderConfig(
                                title = title.ifBlank { "Pure Папка" },
                                showTitle = showTitle,
                                packages = selectedPackages,
                                style2x2 = style2x2,
                                bgAlpha = bgAlpha.toInt(),
                                bgColorHex = bgColorHex,
                                cornerRadiusDp = cornerRadiusDp.toInt(),
                                iconPaddingDp = iconPaddingDp.toInt()
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeConfig.accentColor,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Сохранить папку (${selectedPackages.size} прил.)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = "Кастомизация папки",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = themeConfig.accentColor
                )
            }

            // 1. Интерактивный макет папки с сортировкой перетаскиванием + кнопка добавления
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Макет папки",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Зажмите иконку для перетаскивания • ✕ удалить",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = themeConfig.accentColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${selectedPackages.size} шт.",
                                color = themeConfig.accentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Визуальный контейнер самой папки
                    FolderMockupReorderGrid(
                        selectedApps = selectedAppItems,
                        itemsPerPage = style2x2.itemsPerPage,
                        title = title,
                        showTitle = showTitle,
                        bgColorHex = bgColorHex,
                        bgAlpha = bgAlpha.toInt(),
                        cornerRadiusDp = cornerRadiusDp.toInt(),
                        accentColor = themeConfig.accentColor,
                        onMove = { fromIndex, toIndex ->
                            if (fromIndex in selectedPackages.indices && toIndex in selectedPackages.indices && fromIndex != toIndex) {
                                val mutable = selectedPackages.toMutableList()
                                val item = mutable.removeAt(fromIndex)
                                mutable.add(toIndex, item)
                                selectedPackages = mutable
                            }
                        },
                        onRemove = { pkg ->
                            selectedPackages = selectedPackages - pkg
                        },
                        onEmptyClick = { showAppPickerSheet = true }
                    )

                    // Кнопка вызова списка всех приложений
                    OutlinedButton(
                        onClick = { showAppPickerSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, themeConfig.accentColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = themeConfig.accentColor)
                    ) {
                        Text(
                            text = "+ Добавить приложения",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            // 2. Название и показ заголовка
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название папки") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Показывать заголовок в папке", color = Color.White, fontSize = 14.sp)
                        PureSwitch(
                            checked = showTitle,
                            onCheckedChange = { showTitle = it }
                        )
                    }
                }
            }

            // 3. Раскладка для большой папки 2x2
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Вид большой папки (2×2)",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )

                    BigFolderStyle.entries.forEach { style ->
                        val isSelected = style2x2 == style
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) themeConfig.accentColor.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .clickable { style2x2 = style }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = style.title,
                                color = if (isSelected) themeConfig.accentColor else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = { style2x2 = style },
                                colors = RadioButtonDefaults.colors(selectedColor = themeConfig.accentColor)
                            )
                        }
                    }
                }
            }

            // 4. Внешний вид: цвет, прозрачность, углы, отступы
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Оформление и сетка",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(colorPresets) { (hex, name) ->
                            val selected = bgColorHex == hex
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF2B2B2B))
                                    .border(
                                        width = if (selected) 1.5.dp else 0.dp,
                                        color = if (selected) themeConfig.accentColor else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { bgColorHex = hex }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF000000 or hex.toLong()))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(name, fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }

                    Text(
                        "Прозрачность фона: ${(bgAlpha / 255f * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = bgAlpha,
                        onValueChange = { bgAlpha = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(
                            thumbColor = themeConfig.accentColor,
                            activeTrackColor = themeConfig.accentColor
                        )
                    )

                    Text(
                        "Скругление углов: ${cornerRadiusDp.toInt()} dp",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = cornerRadiusDp,
                        onValueChange = { cornerRadiusDp = it },
                        valueRange = 8f..44f,
                        colors = SliderDefaults.colors(
                            thumbColor = themeConfig.accentColor,
                            activeTrackColor = themeConfig.accentColor
                        )
                    )

                    Text(
                        "Отступ иконок: ${iconPaddingDp.toInt()} dp",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = iconPaddingDp,
                        onValueChange = { iconPaddingDp = it },
                        valueRange = 2f..16f,
                        colors = SliderDefaults.colors(
                            thumbColor = themeConfig.accentColor,
                            activeTrackColor = themeConfig.accentColor
                        )
                    )
                }
            }
        }
    }
}

/**
 * Макет папки со свайпом по слайдам (HorizontalPager), сортировкой перетаскиванием
 * и плавно перетекающим индикатором точек в стиле OxygenOS
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderMockupReorderGrid(
    selectedApps: List<InstalledAppItem>,
    itemsPerPage: Int,
    title: String,
    showTitle: Boolean,
    bgColorHex: Int,
    bgAlpha: Int,
    cornerRadiusDp: Int,
    accentColor: Color,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onEmptyClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val currentApps by rememberUpdatedState(selectedApps)
    val currentOnMove by rememberUpdatedState(onMove)

    val pages = remember(selectedApps, itemsPerPage) {
        if (selectedApps.isEmpty()) emptyList() else selectedApps.chunked(itemsPerPage)
    }
    val pageCount = pages.size.coerceAtLeast(1)
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pageCount })

    val folderShape = RoundedCornerShape(cornerRadiusDp.dp)
    val folderBgColor = Color(0xFF000000 or bgColorHex.toLong()).copy(
        alpha = (bgAlpha / 255f).coerceIn(0.25f, 0.98f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 210.dp)
            .clip(folderShape)
            .background(folderBgColor)
            .border(1.dp, Color.White.copy(alpha = 0.18f), folderShape)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selectedApps.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEmptyClick() }
                    .padding(vertical = 32.dp)
            ) {
                Text(
                    text = "Папка пуста",
                    color = Color.White.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Нажмите «+ Добавить приложения» ниже",
                    color = accentColor,
                    fontSize = 12.sp
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showTitle && title.isNotBlank()) title else "Слайд ${pagerState.currentPage + 1} из $pageCount",
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Свайп влево/вправо • Зажатие — сорт.",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }

                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { pageIdx ->
                    val slideApps = pages.getOrElse(pageIdx) { emptyList() }
                    val pageStartGlobalIdx = pageIdx * itemsPerPage

                    var draggingPkg by remember { mutableStateOf<String?>(null) }
                    var dragOffset by remember { mutableStateOf(Offset.Zero) }
                    val itemBounds = remember { mutableStateMapOf<String, Pair<Offset, IntSize>>() }

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = 3
                    ) {
                        slideApps.forEachIndexed { localIdx, app ->
                            val globalIndex = pageStartGlobalIdx + localIdx
                            key(app.packageName) {
                                val isDragging = draggingPkg == app.packageName
                                val scale by animateFloatAsState(
                                    targetValue = if (isDragging) 1.15f else 1f,
                                    label = "DragScale"
                                )

                                Box(
                                    modifier = Modifier
                                        .width(80.dp)
                                        .zIndex(if (isDragging) 10f else 1f)
                                        .onGloballyPositioned { coords ->
                                            itemBounds[app.packageName] =
                                                coords.positionInParent() to coords.size
                                        }
                                        .graphicsLayer {
                                            translationX = if (isDragging) dragOffset.x else 0f
                                            translationY = if (isDragging) dragOffset.y else 0f
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .pointerInput(app.packageName) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    draggingPkg = app.packageName
                                                    dragOffset = Offset.Zero
                                                },
                                                onDragEnd = {
                                                    draggingPkg = null
                                                    dragOffset = Offset.Zero
                                                },
                                                onDragCancel = {
                                                    draggingPkg = null
                                                    dragOffset = Offset.Zero
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffset += dragAmount

                                                    val startInfo = itemBounds[app.packageName]
                                                        ?: return@detectDragGesturesAfterLongPress
                                                    val currentCenter = Offset(
                                                        x = startInfo.first.x + startInfo.second.width / 2f + dragOffset.x,
                                                        y = startInfo.first.y + startInfo.second.height / 2f + dragOffset.y
                                                    )

                                                    val targetEntry = itemBounds.entries.firstOrNull { (otherPkg, pair) ->
                                                        if (otherPkg == app.packageName) return@firstOrNull false
                                                        val (pos, size) = pair
                                                        val otherCenter = Offset(
                                                            x = pos.x + size.width / 2f,
                                                            y = pos.y + size.height / 2f
                                                        )
                                                        hypot(
                                                            currentCenter.x - otherCenter.x,
                                                            currentCenter.y - otherCenter.y
                                                        ) < size.width * 0.48f
                                                    }

                                                    if (targetEntry != null) {
                                                        val fromIdx = currentApps.indexOfFirst { it.packageName == app.packageName }
                                                        val toIdx = currentApps.indexOfFirst { it.packageName == targetEntry.key }
                                                        if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                            val oldPos = startInfo.first
                                                            val newPos = targetEntry.value.first
                                                            dragOffset -= (newPos - oldPos)
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            currentOnMove(fromIdx, toIdx)
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isDragging) accentColor.copy(alpha = 0.25f)
                                            else Color.White.copy(alpha = 0.07f)
                                        )
                                        .border(
                                            width = if (isDragging) 1.5.dp else 0.dp,
                                            color = if (isDragging) accentColor else Color.Transparent,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(modifier = Modifier.size(52.dp)) {
                                            val bmp = remember(app.packageName) {
                                                PureFolderWidgetProvider.drawableToBitmap(app.icon, 128).asImageBitmap()
                                            }
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = app.label,
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .align(Alignment.Center)
                                            )

                                            Surface(
                                                shape = CircleShape,
                                                color = accentColor,
                                                modifier = Modifier.align(Alignment.TopStart)
                                            ) {
                                                Text(
                                                    text = "${globalIndex + 1}",
                                                    color = Color.Black,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }

                                            Surface(
                                                shape = CircleShape,
                                                color = Color(0xFFFF5252),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .align(Alignment.TopEnd)
                                                    .clickable { onRemove(app.packageName) }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = "✕",
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Text(
                                            text = app.label,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Плавный индикатор точек OxygenOS (одна точка перетекает во вторую при свайпе)
                if (pageCount > 1) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OxygenMorphingDots(
                        pagerState = pagerState,
                        pageCount = pageCount,
                        activeColor = accentColor
                    )
                }
            }
        }
    }
}

/**
 * Индикатор в стиле OxygenOS: при свайпе активная капсула плавно растягивается
 * и перетекает из одной точки в следующую на основе currentPageOffsetFraction
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OxygenMorphingDots(
    pagerState: androidx.compose.foundation.pager.PagerState,
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
            // Вычисляем степень активности каждой точки с учетом дробного сдвига свайпа
            val distance = kotlin.math.abs((currentPage + offsetFraction) - i).coerceIn(0f, 1f)
            val activeFraction = 1f - distance

            // Ширина плавно перетекает от 7.dp (обычная точка) до 22.dp (активная капсула)
            val widthDp = 7.dp + (15.dp * activeFraction)
            val dotColor = androidx.compose.ui.graphics.lerp(
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

/**
 * Модальное окно выбора приложений:
 * - Центрированная сетка на 5 столбцов (или 3..5 по ширине экрана)
 * - Сортировка строго от А-Я до A-Z
 * - Выбранное приложение остаётся на своём месте и обводится рамкой
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerBottomSheet(
    allApps: List<InstalledAppItem>,
    selectedPackages: List<String>,
    accentColor: Color,
    onToggleApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    // Фильтрация без изменения позиции выбранных элементов!
    val displayedApps = remember(allApps, searchQuery) {
        if (searchQuery.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Выбор приложений",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Сортировка: А–Я, затем A–Z • Выбрано: ${selectedPackages.size}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text("Готово", color = accentColor, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Поиск по названию...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                // По умолчанию стремимся к 5 столбцам (на узких экранах минимум 3)
                val columnsCount = (maxWidth / 68.dp).toInt().coerceIn(3, 5)

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnsCount),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedApps, key = { it.packageName }) { app ->
                        val isSelected = selectedPackages.contains(app.packageName)
                        val orderNum = selectedPackages.indexOf(app.packageName) + 1

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) accentColor.copy(alpha = 0.14f)
                                    else Color(0xFF222222)
                                )
                                .border(
                                    width = if (isSelected) 1.8.dp else 0.dp,
                                    color = if (isSelected) accentColor else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onToggleApp(app.packageName) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                val bmp = remember(app.packageName) {
                                    PureFolderWidgetProvider.drawableToBitmap(app.icon, 96).asImageBitmap()
                                }
                                Image(
                                    bitmap = bmp,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(42.dp)
                                )

                                if (isSelected) {
                                    Surface(
                                        shape = CircleShape,
                                        color = accentColor,
                                        modifier = Modifier.offset(x = 4.dp, y = (-4).dp)
                                    ) {
                                        Text(
                                            text = "$orderNum",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = app.label,
                                color = if (isSelected) accentColor else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )


                        }
                    }
                }
            }
        }
    }
}
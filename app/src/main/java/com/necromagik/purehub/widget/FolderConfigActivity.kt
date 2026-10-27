package com.necromagik.purehub.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necromagik.purehub.ui.components.PureSwitch
import com.necromagik.purehub.ui.theme.PureHUBTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

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

private fun compareAppLabelsCyrillicFirst(a: String, b: String): Int {
    val cleanA = a.trim()
    val cleanB = b.trim()
    val firstA = cleanA.firstOrNull()?.uppercaseChar() ?: ' '
    val firstB = cleanB.firstOrNull()?.uppercaseChar() ?: ' '

    fun charGroup(c: Char): Int = when {
        c in 'А'..'Я' || c == 'Ё' -> 0
        c in 'A'..'Z' -> 1
        else -> 2
    }

    val groupA = charGroup(firstA)
    val groupB = charGroup(firstB)

    if (groupA != groupB) {
        return groupA.compareTo(groupB)
    }

    val collator = Collator.getInstance(Locale("ru", "RU"))
    return collator.compare(cleanA, cleanB)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderConfigScreen(
    initialConfig: FolderConfig,
    onSave: (FolderConfig) -> Unit
) {
    val context = LocalContext.current

    var title by remember { mutableStateOf(initialConfig.title) }
    var showTitle by remember { mutableStateOf(initialConfig.showTitle) }
    var selectedPackages by remember { mutableStateOf(initialConfig.packages) }
    var style2x2 by remember { mutableStateOf(initialConfig.style2x2) }
    var bgAlpha by remember { mutableFloatStateOf(initialConfig.bgAlpha.toFloat()) }
    var bgColorHex by remember { mutableIntStateOf(initialConfig.bgColorHex) }
    var cornerRadiusDp by remember { mutableFloatStateOf(initialConfig.cornerRadiusDp.toFloat()) }
    var iconPaddingDp by remember { mutableFloatStateOf(initialConfig.iconPaddingDp.toFloat()) }

    // Новые системные настройки кастомизации
    var useSystemMonet by remember { mutableStateOf(initialConfig.useSystemMonet) }
    var accentColorHex by remember { mutableIntStateOf(initialConfig.accentColorHex) }
    var borderAlpha by remember { mutableFloatStateOf(initialConfig.borderAlpha.toFloat()) }
    var showPopupLabels by remember { mutableStateOf(initialConfig.showPopupLabels) }
    var popupHorizontalSwipe by remember { mutableStateOf(initialConfig.popupHorizontalSwipe) }

    var showAppPickerSheet by remember { mutableStateOf(false) }

    val previewConfig = remember(
        useSystemMonet, bgColorHex, accentColorHex
    ) {
        initialConfig.copy(
            useSystemMonet = useSystemMonet,
            bgColorHex = bgColorHex,
            accentColorHex = accentColorHex
        )
    }
    val effectiveBgHex = FolderWidgetPrefs.resolveBgColorHex(context, previewConfig)
    val effectiveAccentHex = FolderWidgetPrefs.resolveAccentColorHex(context, previewConfig)
    val activeAccentColor = Color(0xFF000000 or effectiveAccentHex.toLong())

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
        0x2D1B2E to "Аметист",
        0x2B2318 to "Янтарь"
    )

    val accentPresets = listOf(
        0x00E676 to "Pure Зеленый",
        0x448AFF to "Origin Синий",
        0xFF5252 to "OnePlus Красный",
        0xFFD740 to "Золотой",
        0xE040FB to "Неон",
        0xFFFFFF to "Белый"
    )

    if (showAppPickerSheet) {
        AppPickerBottomSheet(
            allApps = allApps,
            selectedPackages = selectedPackages,
            accentColor = activeAccentColor,
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
                                iconPaddingDp = iconPaddingDp.toInt(),
                                useSystemMonet = useSystemMonet,
                                accentColorHex = accentColorHex,
                                borderAlpha = borderAlpha.toInt(),
                                showPopupLabels = showPopupLabels,
                                popupHorizontalSwipe = popupHorizontalSwipe
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = activeAccentColor,
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
                    color = activeAccentColor
                )
            }

            // 1. Интерактивный макет папки с сортировкой внутри и МЕЖДУ вкладками
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
                                text = "Макет и сортировка вкладок",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Тяните иконку к краю окна для переноса на другую вкладку",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = activeAccentColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${selectedPackages.size} шт.",
                                color = activeAccentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    val folderShape = RoundedCornerShape(cornerRadiusDp.dp)
                    val folderBgColor = Color(0xFF000000 or effectiveBgHex.toLong()).copy(
                        alpha = (bgAlpha / 255f).coerceIn(0.25f, 0.98f)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(folderShape)
                            .background(folderBgColor)
                            .border(
                                width = 1.2.dp,
                                color = Color.White.copy(alpha = (borderAlpha / 255f).coerceIn(0f, 1f)),
                                shape = folderShape
                            )
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedAppItems.isEmpty()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAppPickerSheet = true }
                                    .padding(vertical = 36.dp)
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
                                    color = activeAccentColor,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            FolderInteractivePagerGrid(
                                apps = selectedAppItems,
                                itemsPerPage = style2x2.itemsPerPage,
                                isHorizontalSwipe = popupHorizontalSwipe,
                                showLabels = showPopupLabels,
                                showEditBadges = true,
                                accentColor = activeAccentColor,
                                onMoveApp = { fromIndex, toIndex ->
                                    if (fromIndex in selectedPackages.indices && toIndex in selectedPackages.indices && fromIndex != toIndex) {
                                        val mutable = selectedPackages.toMutableList()
                                        val item = mutable.removeAt(fromIndex)
                                        mutable.add(toIndex, item)
                                        selectedPackages = mutable
                                    }
                                },
                                onRemoveApp = { pkg ->
                                    selectedPackages = selectedPackages - pkg
                                }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showAppPickerSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, activeAccentColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = activeAccentColor)
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

            // 2. Системная интеграция и поведение открытой папки
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
                        text = "Системный стиль и меню папки",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Адаптивный цвет системы (Monet)", color = Color.White, fontSize = 14.sp)
                            Text("Подстраивать фон и капсулу под обои Android 12+", color = Color.Gray, fontSize = 11.sp)
                        }
                        PureSwitch(
                            checked = useSystemMonet,
                            onCheckedChange = { useSystemMonet = it }
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Подписи иконок в открытой папке", color = Color.White, fontSize = 14.sp)
                            Text("Показывать названия под приложениями", color = Color.Gray, fontSize = 11.sp)
                        }
                        PureSwitch(
                            checked = showPopupLabels,
                            onCheckedChange = { showPopupLabels = it }
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Горизонтальный свайп вкладок", color = Color.White, fontSize = 14.sp)
                            Text(
                                if (popupHorizontalSwipe) "Листание влево / вправо" else "Листание вверх / вниз",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                        PureSwitch(
                            checked = popupHorizontalSwipe,
                            onCheckedChange = { popupHorizontalSwipe = it }
                        )
                    }
                }
            }

            // 3. Название и показ заголовка
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
                        Text("Показывать заголовок на виджете", color = Color.White, fontSize = 14.sp)
                        PureSwitch(
                            checked = showTitle,
                            onCheckedChange = { showTitle = it }
                        )
                    }
                }
            }

            // 4. Раскладка для большой папки 2x2
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
                                    if (isSelected) activeAccentColor.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .clickable { style2x2 = style }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = style.title,
                                color = if (isSelected) activeAccentColor else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = { style2x2 = style },
                                colors = RadioButtonDefaults.colors(selectedColor = activeAccentColor)
                            )
                        }
                    }
                }
            }

            // 5. Внешний вид: цвет фона, цвет капсулы, прозрачность, кант, углы
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
                        "Оформление, цвета и геометрия",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )

                    if (!useSystemMonet) {
                        Text("Цвет подложки:", fontSize = 12.sp, color = Color.Gray)
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
                                            color = if (selected) activeAccentColor else Color.Transparent,
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

                        Text("Цвет капсулы точек (OxygenOS):", fontSize = 12.sp, color = Color.Gray)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(accentPresets) { (hex, name) ->
                                val selected = accentColorHex == hex
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2B2B2B))
                                        .border(
                                            width = if (selected) 1.5.dp else 0.dp,
                                            color = if (selected) Color(0xFF000000 or hex.toLong()) else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable { accentColorHex = hex }
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
                            thumbColor = activeAccentColor,
                            activeTrackColor = activeAccentColor
                        )
                    )

                    Text(
                        "Яркость стеклянного канта (рамки): ${(borderAlpha / 255f * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = borderAlpha,
                        onValueChange = { borderAlpha = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(
                            thumbColor = activeAccentColor,
                            activeTrackColor = activeAccentColor
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
                            thumbColor = activeAccentColor,
                            activeTrackColor = activeAccentColor
                        )
                    )

                    Text(
                        "Отступ иконок на виджете: ${iconPaddingDp.toInt()} dp",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = iconPaddingDp,
                        onValueChange = { iconPaddingDp = it },
                        valueRange = 0f..16f,
                        colors = SliderDefaults.colors(
                            thumbColor = activeAccentColor,
                            activeTrackColor = activeAccentColor
                        )
                    )
                }
            }
        }
    }
}

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
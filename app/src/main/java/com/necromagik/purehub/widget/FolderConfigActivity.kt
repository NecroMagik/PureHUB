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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necromagik.purehub.ui.components.PureSwitch
import com.necromagik.purehub.ui.theme.LocalPureHubConfig
import com.necromagik.purehub.ui.theme.PureHUBTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var searchQuery by remember { mutableStateOf("") }

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
                .sortedBy { it.label.lowercase() }
        }
    }

    val filteredApps = remember(allApps, searchQuery, selectedPackages) {
        allApps.filter {
            searchQuery.isBlank() || it.label.contains(searchQuery, ignoreCase = true)
        }.sortedByDescending { selectedPackages.contains(it.packageName) }
    }

    val colorPresets = listOf(
        0x1E1E1E to "Графит",
        0x000000 to "Черный OLED",
        0x0D291E to "Изумруд",
        0x1A233A to "Полночь",
        0x2D1B2E to "Аметист"
    )

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

            // 1. Название и показ заголовка
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

            // 2. Раскладка для большой папки 2x2
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

            // 3. Внешний вид: цвет, прозрачность, углы, отступы
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Оформление и сетка", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)

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

                    Text("Прозрачность фона: ${(bgAlpha / 255f * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = bgAlpha,
                        onValueChange = { bgAlpha = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = themeConfig.accentColor, activeTrackColor = themeConfig.accentColor)
                    )

                    Text("Скругление углов: ${cornerRadiusDp.toInt()} dp", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = cornerRadiusDp,
                        onValueChange = { cornerRadiusDp = it },
                        valueRange = 8f..44f,
                        colors = SliderDefaults.colors(thumbColor = themeConfig.accentColor, activeTrackColor = themeConfig.accentColor)
                    )

                    Text("Отступ иконок: ${iconPaddingDp.toInt()} dp", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = iconPaddingDp,
                        onValueChange = { iconPaddingDp = it },
                        valueRange = 2f..16f,
                        colors = SliderDefaults.colors(thumbColor = themeConfig.accentColor, activeTrackColor = themeConfig.accentColor)
                    )
                }
            }

            // 4. Поиск и выбор приложений
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Поиск приложений...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            items(filteredApps, key = { it.packageName }) { app ->
                val isChecked = selectedPackages.contains(app.packageName)
                val orderIndex = selectedPackages.indexOf(app.packageName)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            selectedPackages = if (isChecked) {
                                selectedPackages - app.packageName
                            } else {
                                selectedPackages + app.packageName
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val bmp = remember(app.packageName) {
                            PureFolderWidgetProvider.drawableToBitmap(app.icon, 96).asImageBitmap()
                        }
                        Image(
                            bitmap = bmp,
                            contentDescription = app.label,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(app.label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(app.packageName, color = Color.Gray, fontSize = 11.sp)
                        }
                    }

                    if (isChecked) {
                        Surface(
                            shape = CircleShape,
                            color = themeConfig.accentColor
                        ) {
                            Text(
                                text = "${orderIndex + 1}",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        Checkbox(
                            checked = false,
                            onCheckedChange = {
                                selectedPackages = selectedPackages + app.packageName
                            }
                        )
                    }
                }
            }
        }
    }
}
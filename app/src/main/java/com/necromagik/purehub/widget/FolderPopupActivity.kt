package com.necromagik.purehub.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necromagik.purehub.ui.theme.LocalPureHubConfig
import com.necromagik.purehub.ui.theme.PureHUBTheme

class FolderPopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(
            PureFolderWidgetProvider.EXTRA_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
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
                            .fillMaxWidth(0.85f)
                            .heightIn(min = 200.dp, max = 400.dp)
                            .clip(RoundedCornerShape(config.cornerRadiusDp.dp))
                            .background(
                                Color(0xFF000000 or config.bgColorHex.toLong()).copy(
                                    alpha = (config.bgAlpha / 255f).coerceIn(0.5f, 0.98f)
                                )
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {}
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                                    }
                                    context.startActivity(editIntent)
                                    finish()
                                }
                            ) {
                                Text("⚙ Изменить", color = themeConfig.accentColor, fontSize = 12.sp)
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(apps) { (pkg, label, drawable) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            pm.getLaunchIntentForPackage(pkg)?.let {
                                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(it)
                                            }
                                            finish()
                                        }
                                        .padding(6.dp)
                                ) {
                                    val bmp = remember(pkg) {
                                        PureFolderWidgetProvider.drawableToBitmap(drawable, 128).asImageBitmap()
                                    }
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = label,
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
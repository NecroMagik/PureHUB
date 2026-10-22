package com.necromagik.purehub.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt

data class PureHubThemeConfig(
    val accentColor: Color = Color(0xFF00E676),
    val cardCornerRadius: Dp = 20.dp,
    val isAmoled: Boolean = false,
    val is3dEnabled: Boolean = true,
    val isGlowEnabled: Boolean = true,
    val depthIntensityDp: Dp = 8.dp
)

val LocalPureHubConfig = staticCompositionLocalOf { PureHubThemeConfig() }

object SystemThemeUtils {
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    fun isDynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun getSystemAccentHex(context: Context): String {
        return if (isDynamicColorSupported()) {
            try {
                val dynamicScheme = dynamicDarkColorScheme(context)
                val argb = dynamicScheme.primary.toArgb()
                String.format("#%06X", 0xFFFFFF and argb)
            } catch (_: Exception) {
                "#00E676"
            }
        } else {
            "#00E676"
        }
    }

    @Composable
    @ReadOnlyComposable
    fun rememberSystemAccentColor(): Color {
        val context = LocalContext.current
        return Color(getSystemAccentHex(context).toColorInt())
    }

    fun getContrastingColor(backgroundColor: Color): Color {
        return if (backgroundColor.luminance() > 0.45f) Color.Black else Color.White
    }
}

fun Modifier.pure3DEffect(
    shape: Shape = RoundedCornerShape(20.dp),
    accentColor: Color = Color(0xFF00E676),
    depthDp: Dp = 8.dp,
    is3dEnabled: Boolean = true,
    isGlowEnabled: Boolean = true,
    surfaceColor: Color = Color(0xFF141417)
): Modifier {
    if (!is3dEnabled) {
        return this
            .clip(shape)
            .background(surfaceColor)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.08f), shape = shape)
    }

    val elevation = if (depthDp > 0.dp) depthDp else 4.dp
    val ambientAlpha = if (isGlowEnabled) 0.55f else 0.25f
    val borderAlpha = if (isGlowEnabled) 0.50f else 0.20f

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = accentColor.copy(alpha = ambientAlpha),
            spotColor = accentColor.copy(alpha = if (isGlowEnabled) 0.35f else 0.15f)
        )
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    surfaceColor.copy(alpha = 1f),
                    surfaceColor.copy(alpha = 0.82f)
                )
            )
        )
        .border(
            width = 1.2.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = borderAlpha),
                    Color.White.copy(alpha = 0.15f),
                    accentColor.copy(alpha = borderAlpha * 0.2f),
                    Color.Transparent
                )
            ),
            shape = shape
        )
}

@Composable
fun PureHUBTheme(
    themeMode: String = "SYSTEM",
    isPureMonocolor: Boolean = false,
    accentColor: Color = SystemThemeUtils.rememberSystemAccentColor(),
    cardCornerRadiusDp: Int = 20,
    is3dEnabled: Boolean = true,
    isGlowEnabled: Boolean = true,
    depthIntensityDp: Int = 8,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }

    val darkBackground = if (isPureMonocolor) Color(0xFF000000) else Color(0xFF0C0D11)
    val darkSurface = if (isPureMonocolor) Color(0xFF141417) else Color(0xFF171920)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accentColor,
            onPrimary = SystemThemeUtils.getContrastingColor(accentColor),
            background = darkBackground,
            onBackground = Color.White,
            surface = darkSurface,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF22242D),
            onSurfaceVariant = Color(0xFFB0B3C0)
        )
    } else {
        lightColorScheme(
            primary = accentColor,
            onPrimary = SystemThemeUtils.getContrastingColor(accentColor),
            background = Color(0xFFF3F4F8),
            onBackground = Color.Black,
            surface = Color(0xFFFEFEFE),
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFE3E5ED),
            onSurfaceVariant = Color(0xFF5B5E6E)
        )
    }

    val config = PureHubThemeConfig(
        accentColor = accentColor,
        cardCornerRadius = cardCornerRadiusDp.dp,
        isAmoled = isPureMonocolor,
        is3dEnabled = is3dEnabled,
        isGlowEnabled = isGlowEnabled,
        depthIntensityDp = depthIntensityDp.dp
    )

    CompositionLocalProvider(LocalPureHubConfig provides config) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
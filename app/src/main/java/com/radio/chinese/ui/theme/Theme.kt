package com.radio.chinese.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF545F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color.White,
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    background = Color(0xFF1A1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF)
)

@Composable
fun ChineseRadioTheme(
    themeMode: Int = 0, // 0 = system, 1 = light, 2 = dark
    fontScale: Float = 1f,
    uiMode: Int = 0,    // 0 = 自动, 1 = 手机, 2 = 车机
    content: @Composable () -> Unit
) {
    val surface = rememberUiSurface(uiMode)
    // 车机档取字号“下限”而不是叠乘：标准档在车机上按「大」渲染，
    // 用户仍可选超大(1.3)，不会出现 1.3×1.15 那种失控放大。
    val effectiveScale = if (surface == UiSurface.Car) maxOf(fontScale, 1.15f) else fontScale

    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    // 刻意不接入 dynamicColorScheme：Android 12+ 的系统取色会完全覆盖下面的品牌蓝，
    // 导致同一个 App 在不同壁纸下长得不一样、应用市场截图与真机不一致。
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalUiSurface provides surface) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(effectiveScale),
            shapes = AppShapes,
            content = content
        )
    }
}

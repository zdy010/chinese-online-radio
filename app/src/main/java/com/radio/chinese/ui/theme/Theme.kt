package com.radio.chinese.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 经典蓝基线。internal 而不是 private：
 * ThemeContrastTest 要断言三档经典主题返回的仍是这两个实例本身（引用相等），
 * 派生规则一旦把它们也改了，回归基线就悄悄没了。
 */
internal val LightColorScheme = lightColorScheme(
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

internal val DarkColorScheme = darkColorScheme(
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

/**
 * 当前配色是深色还是浅色。可用性色这类「不属于 ColorScheme、但必须随深浅换明度」的
 * 颜色只有这一个取值入口——页面里再算一次 isSystemInDarkTheme() 就是第二套结论。
 */
val LocalIsDarkTheme = compositionLocalOf { false }

@Composable
fun ChineseRadioTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    fontScale: Float = 1f,
    uiMode: Int = 0,    // 0 = 自动, 1 = 手机, 2 = 车机
    content: @Composable () -> Unit
) {
    val surface = rememberUiSurface(uiMode)
    // 车机档取字号“下限”而不是叠乘：标准档在车机上按「大」渲染，
    // 用户仍可选超大(1.3)，不会出现 1.3×1.15 那种失控放大。
    val effectiveScale = if (surface == UiSurface.Car) maxOf(fontScale, 1.15f) else fontScale

    // 深浅的唯一结论。套餐式的代价在这儿：用户选了强制浅色的主题，
    // 系统深浅就必须让位，否则「经典蓝·日间」在深色系统里会变成夜间。
    val dark = theme.isDarkConsidering(isSystemInDarkTheme())

    // 刻意不接入 dynamicColorScheme：Android 12+ 的系统取色会完全覆盖下面的品牌蓝，
    // 导致同一个 App 在不同壁纸下长得不一样、应用市场截图与真机不一致。
    val colorScheme = theme.resolveColorScheme(dark)

    // enableEdgeToEdge() 的状态栏/导航栏图标明暗默认跟系统深浅走。用户选
    // 「经典蓝·日间」而系统是深色时，图标会变成浅底浅字、看不见。必须在这里纠正：
    // 放到 MainActivity 里算会出现两套深浅（主题一套、Activity 一套）。
    val view = LocalView.current
    LaunchedEffect(dark, view) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalUiSurface provides surface,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(effectiveScale),
            shapes = AppShapes,
            content = content
        )
    }
}

package com.radio.chinese.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** 中性层三个锚点：底色、正文、次级文字。其余中性/色调槽位由 tonal 派生。 */
private data class Neutral(val base: Color, val ink: Color, val inkSoft: Color)

/**
 * 强调层逐个手给：容器色的「降饱和」用一个插值算法做不出品牌感，
 * 朱砂与松绿的容器色不是一个公式能通用的。
 */
private data class Accent(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/** spec §5.2 的 tone 表。改动要先过 ThemeContrastTest，别凭观感调。 */
private data class Tones(
    val containerLowest: Float,
    val containerLow: Float,
    val container: Float,
    val containerHigh: Float,
    val containerHighest: Float,
    val bright: Float,
    val dim: Float,
    val variant: Float,
    val outline: Float,
    val outlineVariant: Float,
    val inverseSurface: Float,
    val inverseOnSurface: Float,
)

// outline 暗档 0.50：0.42 时 amber 只有 2.9:1、soft_dark 3.02:1，不达非文本 3:1
private val DarkTones = Tones(0.00f, 0.03f, 0.05f, 0.08f, 0.12f, 0.16f, 0.00f, 0.16f, 0.50f, 0.34f, 0.88f, 0.06f)
private val LightTones = Tones(0.00f, 0.01f, 0.03f, 0.05f, 0.08f, 0.00f, 0.12f, 0.08f, 0.48f, 0.30f, 0.88f, 0.12f)

/**
 * 中性 + 色调面板一次性铺好。
 *
 * 必须连 surfaceContainer* 一起接管：material3 1.3.1 的这些槽位有库自带固定灰阶，
 * 只改 background/surface 的话，AlertDialog / Card / 下拉菜单在黑底上会浮出灰块、
 * 暖底上会露出冷灰——恰好在最需要成套的三个主题上破功。
 */
private fun ColorScheme.withNeutral(n: Neutral, dark: Boolean): ColorScheme {
    val tones = if (dark) DarkTones else LightTones
    fun t(v: Float) = tonal(n.base, n.ink, v)
    return copy(
        background = n.base,
        onBackground = n.ink,
        surface = n.base,
        onSurface = n.ink,
        onSurfaceVariant = n.inkSoft,
        surfaceVariant = t(tones.variant),
        surfaceDim = t(tones.dim),
        surfaceBright = t(tones.bright),
        surfaceContainerLowest = t(tones.containerLowest),
        surfaceContainerLow = t(tones.containerLow),
        surfaceContainer = t(tones.container),
        surfaceContainerHigh = t(tones.containerHigh),
        surfaceContainerHighest = t(tones.containerHighest),
        outline = t(tones.outline),
        outlineVariant = t(tones.outlineVariant),
        inverseSurface = t(tones.inverseSurface),
        inverseOnSurface = t(tones.inverseOnSurface),
    )
}

/**
 * 强调层装配。
 *
 * secondary 主色恒等于 primary：全站没有任何一处直接消费 secondary 主色
 * （chips / FilterChip / ListItem 取的都是 secondaryContainer），
 * 为它另编一套降饱和值是凭空造一个无人消费的维度。要改回来必须先出现真实调用点。
 *
 * 暗档的 on 主色用 0.18 而不是 0.25：0.25 在 soft_dark 上只有 4.1:1，不达正文 4.5:1。
 */
private fun ColorScheme.withAccent(a: Accent, base: Color, dark: Boolean): ColorScheme {
    fun onOf(main: Color) = if (dark) tonal(base, main, 0.18f) else Color.White
    return copy(
        primary = a.primary,
        onPrimary = onOf(a.primary),
        primaryContainer = a.primaryContainer,
        onPrimaryContainer = a.onPrimaryContainer,
        secondary = a.primary,
        onSecondary = onOf(a.primary),
        secondaryContainer = a.secondaryContainer,
        onSecondaryContainer = a.onSecondaryContainer,
        tertiary = a.tertiary,
        onTertiary = onOf(a.tertiary),
        tertiaryContainer = a.tertiaryContainer,
        onTertiaryContainer = a.onTertiaryContainer,
        inversePrimary = a.primary,
    )
}

private fun schemeOf(dark: Boolean, n: Neutral, a: Accent): ColorScheme =
    (if (dark) DarkColorScheme else LightColorScheme)
        .withNeutral(n, dark)
        .withAccent(a, n.base, dark)

// error / onError / errorContainer / onErrorContainer 一律不覆写：
// M3 的浅深两套本身已按 tone 配好，本期无诉求。

private val SoftDark = schemeOf(
    dark = true,
    n = Neutral(Color(0xFF232326), Color(0xFFD6D3D9), Color(0xFFB0B2B8)),
    a = Accent(
        primary = Color(0xFF86AEDC), primaryContainer = Color(0xFF2C4A68), onPrimaryContainer = Color(0xFFD6E7F7),
        secondaryContainer = Color(0xFF3A3F47), onSecondaryContainer = Color(0xFFCFD4DB),
        tertiary = Color(0xFFB79CC4), tertiaryContainer = Color(0xFF453A4E), onTertiaryContainer = Color(0xFFE2D6E9),
    ),
)

private val PureBlack = schemeOf(
    dark = true,
    n = Neutral(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFC9CDD3)),
    a = Accent(
        primary = Color(0xFFA8C8FF), primaryContainer = Color(0xFF143A5E), onPrimaryContainer = Color(0xFFCFE3FF),
        secondaryContainer = Color(0xFF26282E), onSecondaryContainer = Color(0xFFD3D7DD),
        tertiary = Color(0xFFC7A8D6), tertiaryContainer = Color(0xFF3A2F42), onTertiaryContainer = Color(0xFFEBDDF1),
    ),
)

private val Amber = schemeOf(
    dark = true,
    n = Neutral(Color(0xFF1A1206), Color(0xFFF0C070), Color(0xFFD2AE74)),
    a = Accent(
        primary = Color(0xFFFFB74D), primaryContainer = Color(0xFF5C3D10), onPrimaryContainer = Color(0xFFFFDCA8),
        secondaryContainer = Color(0xFF3A2C14), onSecondaryContainer = Color(0xFFE8D3A8),
        tertiary = Color(0xFFE0A96D), tertiaryContainer = Color(0xFF4A3315), onTertiaryContainer = Color(0xFFF5DCBE),
    ),
)

// 亮白强对比：onSurfaceVariant 不许退成中灰，否则「强对比」名不副实
private val HighContrast = schemeOf(
    dark = false,
    n = Neutral(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF1A1A1A)),
    a = Accent(
        primary = Color(0xFF0B4EA2), primaryContainer = Color(0xFFD6E6FA), onPrimaryContainer = Color(0xFF001F38),
        secondaryContainer = Color(0xFFE4E4E4), onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFF8A4B00), tertiaryContainer = Color(0xFFFFE0B8), onTertiaryContainer = Color(0xFF2B1600),
    ),
)

private val ChinaRed = schemeOf(
    dark = false,
    n = Neutral(Color(0xFFFFFBF7), Color(0xFF1F1A19), Color(0xFF524341)),
    a = Accent(
        primary = Color(0xFFA31F20), primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF410005),
        secondaryContainer = Color(0xFFF5E6C8), onSecondaryContainer = Color(0xFF2E2000),
        tertiary = Color(0xFF7A5C3E), tertiaryContainer = Color(0xFFEEDFC9), onTertiaryContainer = Color(0xFF2E1F0E),
    ),
)

private val InkGreen = schemeOf(
    dark = false,
    n = Neutral(Color(0xFFF7FAF6), Color(0xFF171D18), Color(0xFF41483F)),
    a = Accent(
        primary = Color(0xFF14603C), primaryContainer = Color(0xFFCDE8D6), onPrimaryContainer = Color(0xFF00210F),
        secondaryContainer = Color(0xFFD3E4DA), onSecondaryContainer = Color(0xFF1C3226),
        tertiary = Color(0xFF7A5C3E), tertiaryContainer = Color(0xFFEEDFC9), onTertiaryContainer = Color(0xFF2C1E0B),
    ),
)

/**
 * 主题 → 配色。全树唯一的取色出口。
 *
 * 三档经典主题返回 Theme.kt 里的基线实例本身（不是等值重建），
 * 这样「默认主题逐像素不变」这条回归基线是可以被单测证伪的。
 */
internal fun AppTheme.resolveColorScheme(dark: Boolean): ColorScheme = when (this) {
    AppTheme.SYSTEM -> if (dark) DarkColorScheme else LightColorScheme
    AppTheme.CLASSIC_LIGHT -> LightColorScheme
    AppTheme.CLASSIC_DARK -> DarkColorScheme
    AppTheme.SOFT_DARK -> SoftDark
    AppTheme.PURE_BLACK -> PureBlack
    AppTheme.AMBER -> Amber
    AppTheme.HIGH_CONTRAST -> HighContrast
    AppTheme.CHINA_RED -> ChinaRed
    AppTheme.INK_GREEN -> InkGreen
}

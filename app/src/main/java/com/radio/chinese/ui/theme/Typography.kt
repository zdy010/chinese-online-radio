package com.radio.chinese.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 设置页可选的字阶档位，面向中老年用户。 */
enum class FontScaleOption(val key: String, val label: String, val factor: Float) {
    STANDARD("standard", "标准", 1f),
    LARGE("large", "大", 1.15f),
    EXTRA_LARGE("extra_large", "超大", 1.3f);

    companion object {
        fun fromKey(key: String): FontScaleOption = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/**
 * 中文字阶。此前三套默认 Material 字阶把全站 52% 的文本压在 11–12sp，
 * 且 letterSpacing 是为拉丁大写字母设计的——中文加正字距会把笔画拆散。
 *
 * 这里统一：最小档抬到 12sp 起、正文 15/17sp、行高约 1.5 倍、字距一律 0。
 * 只改这一处即可整体放大全站字号，无需逐页调整。
 */
fun appTypography(factor: Float = 1f): Typography {
    fun s(v: Float) = (v * factor).sp
    fun lh(v: Float) = (v * factor).sp

    fun style(size: Float, line: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontSize = s(size),
        lineHeight = lh(line),
        fontWeight = weight,
        letterSpacing = 0.sp
    )

    return Typography(
        displayLarge = style(34f, 42f, FontWeight.Bold),
        displayMedium = style(30f, 38f, FontWeight.Bold),
        displaySmall = style(26f, 34f, FontWeight.Bold),
        headlineLarge = style(28f, 36f, FontWeight.Bold),
        headlineMedium = style(24f, 32f, FontWeight.Bold),
        headlineSmall = style(22f, 30f, FontWeight.Bold),
        titleLarge = style(20f, 28f, FontWeight.Medium),
        titleMedium = style(18f, 26f, FontWeight.Medium),
        titleSmall = style(16f, 24f, FontWeight.Medium),
        bodyLarge = style(17f, 26f),
        bodyMedium = style(15f, 24f),
        bodySmall = style(13f, 20f),
        labelLarge = style(15f, 20f, FontWeight.Medium),
        labelMedium = style(13f, 18f, FontWeight.Medium),
        labelSmall = style(12f, 17f)
    )
}

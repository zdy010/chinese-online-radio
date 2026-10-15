package com.radio.chinese.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.radio.chinese.data.local.SourceQuality

/**
 * 可用性语义色的两套明度档。含义不变（绿=可用 / 黄=一般 / 红=失效），
 * 只是深底上换亮色、浅底上换深色，由 LocalIsDarkTheme 单点决定——
 * 页面里不许各自判深浅，否则同一屏上会出现两套结论。
 *
 * internal 而不是 private：SourceQualityTest 要在 JVM 里逐套断言对比度，
 * 而 @Composable 取色函数在没有 Android 运行时的单测里拿不到。
 */
internal val LightQualityColors = mapOf(
    SourceQuality.GOOD to Color(0xFF1B7A3D),
    SourceQuality.FAIR to Color(0xFF9A6800),
    SourceQuality.BAD to Color(0xFFB3261E),
)

internal val DarkQualityColors = mapOf(
    SourceQuality.GOOD to Color(0xFF5CD67C),
    SourceQuality.FAIR to Color(0xFFF2C14E),
    SourceQuality.BAD to Color(0xFFFF6B60),
)

@Composable
fun sourceQualityColor(quality: SourceQuality): Color =
    if (LocalIsDarkTheme.current) DarkQualityColors.getValue(quality)
    else LightQualityColors.getValue(quality)

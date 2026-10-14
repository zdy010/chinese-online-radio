package com.radio.chinese.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 中性面板取色：从底色向墨色线性插值。
 *
 * 深浅两档共用同一个函数，差别只在调用方的 tone 表——暗档墨是白所以越高越亮，
 * 亮档墨是黑所以越高越暗（M3 亮档本就靠加深表达层级）。
 *
 * 不用 androidx.compose.ui.graphics.lerp：算法完全相同，但 lerp 属于 ui-graphics
 * 运行时入口，而对比度不变式要在纯 JVM 单测里逐槽位跑（见 ThemeContrastTest）。
 */
internal fun tonal(base: Color, ink: Color, tone: Float): Color = Color(
    red = base.red + (ink.red - base.red) * tone,
    green = base.green + (ink.green - base.green) * tone,
    blue = base.blue + (ink.blue - base.blue) * tone,
    alpha = base.alpha,
)

/** WCAG 2.1 相对亮度。通道值 ≤0.03928 走线性段，否则取 2.4 次幂。 */
internal fun luminance(c: Color): Double {
    fun channel(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG 对比度 (L1+0.05)/(L2+0.05)，与传参顺序无关。 */
internal fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = if (la >= lb) la else lb
    val lo = if (la >= lb) lb else la
    return (hi + 0.05) / (lo + 0.05)
}

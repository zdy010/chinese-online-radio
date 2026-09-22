package com.radio.chinese.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全站尺寸 token。此前 ui/ 下有 175 处裸 dp 字面量、22 种取值，导致留白在
 * 16→12→16→12 之间反复跳变，中文界面看起来像没对齐网格。新增代码请一律引用这里。
 */
object Dimens {
    // 间距节奏（4 的倍数）
    val GapTiny: Dp = 4.dp
    val GapSmall: Dp = 8.dp
    val GapMedium: Dp = 12.dp
    val GapLarge: Dp = 16.dp
    val GapXLarge: Dp = 24.dp
    val GapHuge: Dp = 32.dp

    // 布局
    val ScreenPadding: Dp = 16.dp
    val ListGap: Dp = 8.dp
    val CardPadding: Dp = 12.dp
    val RowVerticalPadding: Dp = 12.dp
    val ChipGap: Dp = 8.dp

    // 高度与命中区
    val RowMinHeight: Dp = 56.dp
    val TouchMin: Dp = 48.dp
    val IconSmall: Dp = 18.dp
    val IconMedium: Dp = 24.dp
    val IconLarge: Dp = 32.dp
    val MiniPlayerHeight: Dp = 72.dp
    val OperaMiniPlayerHeight: Dp = 104.dp
    val MiniPlayerProgress: Dp = 2.dp

    // 封面/图标位
    val CoverTiny: Dp = 40.dp
    val CoverSmall: Dp = 48.dp
    val CoverLargeMax: Dp = 220.dp

    // 局部滚动上限
    val SourceListMax: Dp = 160.dp
}

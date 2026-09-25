package com.radio.chinese.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全站尺寸 token，按 [UiSurface] 换档。此前 ui/ 下有 175 处裸 dp 字面量、22 种取值，
 * 导致留白在 16→12→16→12 之间反复跳变，中文界面看起来像没对齐网格。新增代码请一律引用这里。
 *
 * 换档的那几个属性是 `@Composable get()`：调用点写法不变（仍写 Dimens.TouchMin），
 * 但只能在 composable 上下文取值——全项目 127 处调用已逐一核实全部满足。
 * 手机档取值必须与换档前完全一致，这是「手机端零回退」的落点。
 */
object Dimens {
    private val car: Boolean
        @Composable get() = LocalUiSurface.current == UiSurface.Car

    // 间距节奏（4 的倍数，两档共用）
    val GapTiny: Dp = 4.dp
    val GapSmall: Dp = 8.dp
    val GapMedium: Dp = 12.dp
    val GapLarge: Dp = 16.dp
    val GapXLarge: Dp = 24.dp
    val GapHuge: Dp = 32.dp

    // 布局
    val ScreenPadding: Dp
        @Composable get() = if (car) 32.dp else 16.dp
    val ListGap: Dp
        @Composable get() = if (car) 16.dp else 8.dp
    val CardPadding: Dp = 12.dp
    val RowVerticalPadding: Dp = 12.dp
    val ChipGap: Dp = 8.dp

    // 高度与命中区：车机档要容得下颠簸中手指与方向盘操作
    val RowMinHeight: Dp
        @Composable get() = if (car) 88.dp else 56.dp
    val TouchMin: Dp
        @Composable get() = if (car) 88.dp else 48.dp
    val IconSmall: Dp = 18.dp
    val IconMedium: Dp = 24.dp
    val IconLarge: Dp = 32.dp
    val MiniPlayerHeight: Dp
        @Composable get() = if (car) 104.dp else 72.dp
    val OperaMiniPlayerHeight: Dp
        @Composable get() = if (car) 144.dp else 104.dp
    val MiniPlayerProgress: Dp = 2.dp

    // 封面/图标位
    val CoverTiny: Dp = 40.dp
    val CoverSmall: Dp
        @Composable get() = if (car) 72.dp else 48.dp
    val CoverLargeMax: Dp
        @Composable get() = if (car) 320.dp else 220.dp

    // 局部滚动上限
    val SourceListMax: Dp
        @Composable get() = if (car) 280.dp else 160.dp

    // 车机档左侧导航栏宽度（手机档不用导航栏，取 Material3 默认值）
    val RailWidth: Dp
        @Composable get() = if (car) 120.dp else 80.dp

    // 车机档正文限宽：超宽屏上不让内容从屏幕左拉到右，眼睛要横着找控件
    val CarContentMaxWidth: Dp = 1000.dp

    // 播放主控件：替换原先的 GapHuge * 2 与 40.dp 裸值
    val PlayButtonSize: Dp
        @Composable get() = if (car) 96.dp else 64.dp
    val PlayIconSize: Dp
        @Composable get() = if (car) 56.dp else 40.dp
}

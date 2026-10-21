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
    val MiniPlayerProgress: Dp = 2.dp

    // 进度滑条的命中区高度：手机档沿用原来的 32dp；车机档用 64dp——
    // 再往上抬会把三行迷你条撑到 270dp，把列表挤得只剩三行
    val SliderTouchHeight: Dp
        @Composable get() = if (car) 64.dp else 32.dp

    // 封面/图标位
    val CoverTiny: Dp = 40.dp
    val CoverSmall: Dp
        @Composable get() = if (car) 72.dp else 48.dp
    val CoverLargeMax: Dp
        @Composable get() = if (car) 320.dp else 220.dp

    // 局部滚动上限：车机档不能比手机档更高——迷你条/节目源/播控要抢同一屏高度，
    // 把源列表放大到 280dp 会把播放键顶出首屏
    val SourceListMax: Dp = 160.dp

    // 车机档左侧导航栏宽度（手机档不用导航栏，取 Material3 默认值）
    val RailWidth: Dp
        @Composable get() = if (car) 120.dp else 80.dp

    // 地区选择对话框的省份列表滚动上限：34 个省全展开会顶出屏幕，
    // 也不能像电台源列表那样压到 160dp——一屏至少要看得到七八个省才方便找
    val RegionPickerMaxHeight: Dp
        @Composable get() = if (car) 400.dp else 320.dp

    // 车机档正文限宽：超宽屏上不让内容从屏幕左拉到右，眼睛要横着找控件
    val CarContentMaxWidth: Dp = 1000.dp

    // 全屏播放页横屏车机档右侧控件栏宽度（手机档单列不用）。
    // 必须装得下主控件行五个 88dp 按钮（5×88=440）加左右留白；
    // 原先 420dp 会把按钮压到 56dp，命中区直接跌破车机档下限。
    val PlayerSideWidth: Dp
        @Composable get() = if (car) 520.dp else 0.dp

    // 播放主控件：替换原先的 GapHuge * 2 与 40.dp 裸值
    val PlayButtonSize: Dp
        @Composable get() = if (car) 96.dp else 64.dp
    val PlayIconSize: Dp
        @Composable get() = if (car) 56.dp else 40.dp

    // 主题选择对话框的预览色块：车机档要给看不清文字的人一个靠颜色选主题的
    // 抓手，太小就没意义了
    val ThemeSwatchSize: Dp
        @Composable get() = if (car) 32.dp else 24.dp

    // 色块描边宽度：不参与换档，两个形态都用 1dp 勾出边界
    val SwatchBorderWidth: Dp = 1.dp
}

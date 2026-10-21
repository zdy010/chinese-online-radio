package com.radio.chinese.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 界面形态。全树唯一判定源：页面只允许用 [isCarSurface] 分支，
 * 不许各自读 LocalConfiguration 判屏幕，否则同一棵树上会出现两套结论。
 */
enum class UiSurface { Phone, Car }

val LocalUiSurface = compositionLocalOf { UiSurface.Phone }

/**
 * 纯函数判定，便于单点核对。
 *
 * mode：0=自动 / 1=强制手机 / 2=强制车机。
 * 必须有手动档：加装车机上报的 density 极随意，同样 1280x720 的面板，
 * 报 160dpi 时横屏是 1280x720dp（判成车机），报 240dpi 时只有 853x480dp
 * （sw=480，会被判成手机，用户永远等不到车机布局）。
 */
fun resolveUiSurface(mode: Int, smallestWidthDp: Int, landscape: Boolean): UiSurface = when (mode) {
    1 -> UiSurface.Phone
    2 -> UiSurface.Car
    else -> if (smallestWidthDp >= 600 && landscape) UiSurface.Car else UiSurface.Phone
}

/** 随旋转 / 分屏 / 手动改模式重组，不需要重启进程。 */
@Composable
fun rememberUiSurface(mode: Int): UiSurface {
    val cfg = LocalConfiguration.current
    return remember(cfg.smallestScreenWidthDp, cfg.orientation, mode) {
        resolveUiSurface(
            mode,
            cfg.smallestScreenWidthDp,
            cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
    }
}

@Composable
fun isCarSurface(): Boolean = LocalUiSurface.current == UiSurface.Car

/**
 * 车机档下页签栏是否用左侧 NavigationRail。
 *
 * rail 是竖向排列的，4 个条目要占 ≈400dp 高；density 报 240 的车机横屏只有 480dp，
 * 再减掉搜索栏就只装得下三个，“收藏/最近”直接不可达。视口不够高时退回顶部横条，
 * 页签数量多时横着排反而放得下。
 */
@Composable
fun usesRailNavigation(): Boolean =
    isCarSurface() && LocalConfiguration.current.screenHeightDp >= 560

/**
 * 页签栏改用左侧 rail 是否装得下。纯函数，便于单点核对。
 *
 * 高度：rail 竖着排，每个条目 TouchMin=88dp，M3 项间距实测 4dp，按 92dp 计；
 * 屏幕上要先扣掉搜索栏（88dp）与底部导航栏（80dp），剩下的才是 rail 真正拿到的。
 * 原先只判 screenHeightDp >= 560——那是 4 个页签时的经验值。页签加到 5 个以后，
 * 1280x720@160dpi 的车机屏（实测 screenHeightDp=636，720 被状态栏与桌面 taskbar 吃掉 84）
 * 差几 dp 就装不下，「最近」会被裁在屏幕外、点都点不到。
 *
 * 宽度：rail 本身吃掉 120dp。手机竖屏强制车机档时（411dp 宽）内容区只剩 227dp，
 * 卡片文字全部折行，这种情况横着排反而放得下。
 *
 * 装不下就退回顶部横条。
 */
fun railFits(screenWidthDp: Int, screenHeightDp: Int, tabCount: Int): Boolean =
    screenWidthDp - 120 >= 480 && screenHeightDp - 168 >= tabCount * 92 + 4

@Composable
fun canHostRail(tabCount: Int): Boolean {
    if (!isCarSurface()) return false
    val cfg = LocalConfiguration.current
    return railFits(cfg.screenWidthDp, cfg.screenHeightDp, tabCount)
}

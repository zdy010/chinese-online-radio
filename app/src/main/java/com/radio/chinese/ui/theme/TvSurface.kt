package com.radio.chinese.ui.theme

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 电视判定。纯函数部分单独抽出来，因为 TV AVD 在本机建不起来
 * （SDK 没装 cmdline-tools），判定逻辑只能靠 JVM 单测守住。
 *
 * 两段判据：
 * 1. `UiMode` 的 TELEVISION 位 —— 标准 Android TV 都有
 * 2. 部分国产盒子不设这一位，退回「没有触屏 + 声明了 leanback」
 *
 * 自动判定**不保证准**：判据完全依赖系统上报，国产盒子 / 刷了普通 Android 的电视
 * 常常既没有 TELEVISION 位、也不声明 leanback，甚至还报有触屏 —— 会被判成手机。
 * 所以设置里加了「强制电视」档，由 Theme 下发 [LocalTvMode] 覆盖自动结论。
 *
 * 电视复用 Car 档的大屏布局，不把 Tv 做成第三个 UiSurface —— 那会让所有
 * `isCarSurface()` 分支都要重判一遍。TV 专属行为（恒横屏、九键搜索、默认全屏、
 * 遥控器焦点）只看 [LocalTvMode]。
 */
object TvSurface {

    /** `Configuration.UI_MODE_TYPE_TELEVISION` 的值；硬编码避免 JVM 单测触碰 Android stub */
    private const val UI_MODE_TELEVISION = 4

    fun isTv(uiModeType: Int, hasTouchscreen: Boolean, hasLeanback: Boolean): Boolean =
        uiModeType == UI_MODE_TELEVISION || (!hasTouchscreen && hasLeanback)

    /**
     * 手动档优先于自动判定：0=自动取系统特征，1/2（手机/车机）关掉 TV 行为，
     * 3=强制电视。抽成纯函数是为了让「自动判定不准时能自救」这条有单测守着。
     */
    fun resolveTvMode(uiMode: Int, systemIsTv: Boolean): Boolean = when (uiMode) {
        1, 2 -> false
        3 -> true
        else -> systemIsTv
    }
}

fun Context.isTv(): Boolean = TvSurface.isTv(
    uiModeType = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
    hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
    hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
)

/**
 * 当前是否按电视形态渲染。由 Theme 统一下发：
 * 手动档「手机/车机」= false（能推翻自动判定），「电视」= true，自动档取系统特征。
 */
val LocalTvMode = compositionLocalOf { false }

@Composable
fun isTvSurface(): Boolean = LocalTvMode.current

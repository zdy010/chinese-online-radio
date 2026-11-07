package com.radio.chinese.ui.theme

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 电视判定。纯函数部分单独抽出来，因为 TV AVD 在本机建不起来
 * （SDK 没装 cmdline-tools），判定逻辑只能靠 JVM 单测守住。
 *
 * 两段判据：
 * 1. `UiMode` 的 TELEVISION 位 —— 标准 Android TV 都有
 * 2. 部分国产盒子不设这一位，退回「没有触屏 + 声明了 leanback」
 *
 * 为什么不塞进 [UiSurface]：电视复用 Car 档的大屏布局（sw≥600 + 横屏自动命中），
 * 只有三处必须区别对待 —— 恒横屏、遥控器焦点可见、搜索改成九键首字母。
 * 把 Tv 做成第三个 UiSurface 会让所有现有 `isCarSurface()` 分支都要重判一遍。
 */
object TvSurface {

    /** `Configuration.UI_MODE_TYPE_TELEVISION` 的值；硬编码避免 JVM 单测触碰 Android stub */
    private const val UI_MODE_TELEVISION = 4

    fun isTv(uiModeType: Int, hasTouchscreen: Boolean, hasLeanback: Boolean): Boolean =
        uiModeType == UI_MODE_TELEVISION || (!hasTouchscreen && hasLeanback)
}

fun Context.isTv(): Boolean = TvSurface.isTv(
    uiModeType = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
    hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
    hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
)

@Composable
fun isTvSurface(): Boolean = LocalContext.current.isTv()

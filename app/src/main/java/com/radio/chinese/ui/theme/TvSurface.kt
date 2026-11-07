package com.radio.chinese.ui.theme

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 电视形态判定。
 *
 * 先看 `UiMode` 的 TELEVISION 位；部分国产盒子不设这一位，再退回到
 * 「没有触屏 + 声明了 leanback」这个组合。两者都不满足就按手机/车机处理。
 *
 * 为什么不塞进 [UiSurface]：电视复用 Car 档的大屏布局（sw≥600 + 横屏自动命中），
 * 只有三处必须区别对待 —— 恒横屏、遥控器焦点可见、输入框不该是主要入口。
 * 把 Tv 做成第三个 UiSurface 会让所有现有 `isCarSurface()` 分支都要重判一遍。
 */
fun Context.isTv(): Boolean {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = packageManager
    val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return noTouch && leanback
}

@Composable
fun isTvSurface(): Boolean = LocalContext.current.isTv()

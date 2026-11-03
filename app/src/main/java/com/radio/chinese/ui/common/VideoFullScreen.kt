package com.radio.chinese.ui.common

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 视频全屏态的持有者，也是全应用唯一会改屏幕方向与系统栏的地方。
 *
 * 三条硬规矩：
 * 1. 状态用 `rememberSaveable`，系统命令由 `LaunchedEffect(fullScreen)` 统一执行 ——
 *    万一发生重建（例如密度变化不在 configChanges 里，实测仍重建），标志位活下来，
 *    命令会被重放，不会出现「标志说在全屏、系统栏却是显示的」。
 * 2. 返回键先退全屏、不退页面。
 * 3. 离开组合时无条件复位方向与系统栏，不看当前状态 —— 否则用户带着横屏回到列表页。
 *
 * 退出全屏用 SCREEN_ORIENTATION_UNSPECIFIED（跟随系统），不硬锁竖屏：
 * 用户原本竖屏就回竖屏，原本手动横屏就留在横屏，不该被应用掰。
 */
class VideoFullScreenState(
    val isFullScreen: Boolean,
    val toggle: () -> Unit,
)

@Composable
fun rememberVideoFullScreen(): VideoFullScreenState {
    val context = LocalContext.current
    var fullScreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(fullScreen) {
        val activity = context as? android.app.Activity
        if (activity == null) {
            android.util.Log.w("VideoFullScreen", "上下文不是 Activity，全屏命令跳过")
            return@LaunchedEffect
        }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullScreen) {
            // 用固定横屏而不是 SENSOR_LANDSCAPE：sensor 系常量尊重用户的「自动旋转」开关，
            // 开关关掉时这类请求可能被系统忽略；而「全屏就是横屏」不该跟着那个开关变脸。
            // 注意：本轮在模拟器上没能把旋转行为验清楚（自动旋转开/关两种状态下各有一次
            // 不听请求，传感器停在竖屏），只拿到一张横屏实拍为正向证据；
            // 真机（含关掉自动旋转的用户）上需重跑验收脚本 -OnlyV 8。
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 离开页面必须复位，不管当前是不是全屏
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity ?: return@onDispose
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 全屏时返回键归我：先退全屏，第二次才离开页面
    BackHandler(enabled = fullScreen) { fullScreen = false }

    return remember(fullScreen) {
        VideoFullScreenState(fullScreen) { fullScreen = !fullScreen }
    }
}

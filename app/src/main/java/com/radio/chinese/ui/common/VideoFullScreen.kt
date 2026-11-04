package com.radio.chinese.ui.common

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.radio.chinese.domain.model.VideoControlsPolicy
import com.radio.chinese.domain.model.VideoOrientation

/**
 * 视频全屏态的持有者，也是全应用唯一会改屏幕方向与系统栏的地方。
 *
 * @param canRotate 现在是不是"能真的转"的设备（调用方传 `isCarSurface().not()`）。
 *   它**只约束进入全屏时的方向请求**：车机恒横屏，发横屏请求是无意义操作。
 *
 *   退出全屏与离开页面的方向复位**不受它约束**：`isCarSurface()` 会在全屏期间翻转
 *   （sw≥600 的手机一点全屏就横屏、判定当场变 Car），若复位也看它，就会
 *   这次请求发了、下次跳过，把人永久卡在横屏里。
 *   UNSPECIFIED 对恒横屏设备是无害的空操作，所以复位永远执行。
 *
 * 其余硬规矩：
 * 0. 进入全屏一律横屏（除非用户按过旋转按钮）；退出全屏一律交还系统。
 * 1. 状态用 `rememberSaveable`，命令由 `LaunchedEffect` 统一执行 —— 万一发生重建
 *    （密度变化不在 configChanges 里，实测仍重建），标志位活下来、命令被重放。
 * 2. 返回键先退全屏、不退页面。
 * 3. 控制层里不放应用内返回按钮，返回交给系统返回键。
 */
class VideoFullScreenState(
    val isFullScreen: Boolean,
    val isLandscape: Boolean,
    val toggle: () -> Unit,
    val rotate: () -> Unit,
)

@Composable
fun rememberVideoFullScreen(canRotate: Boolean = true): VideoFullScreenState {
    val context = LocalContext.current
    // 用 LocalConfiguration 而不是 context.resources：后者不是快照态，转屏后不会重组，
    // 旋转按钮就会拿着一份过期的"当前是横屏"结论。
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var rotateOverride by rememberSaveable { mutableStateOf<VideoOrientation?>(null) }

    LaunchedEffect(fullScreen, rotateOverride, canRotate) {
        val activity = context as? android.app.Activity
        if (activity == null) {
            android.util.Log.w("VideoFullScreen", "上下文不是 Activity，全屏命令跳过")
            return@LaunchedEffect
        }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullScreen) {
            // 进入全屏：只有可转设备才发方向请求；用户按过旋转按钮时尊重他的选择
            if (canRotate) {
                val target = rotateOverride ?: VideoOrientation.Landscape
                activity.requestedOrientation = when (target) {
                    VideoOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    VideoOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // 退出全屏：不受 canRotate 约束，永远复位
            rotateOverride = null
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 离开页面必须复位，不管当前是不是全屏、也不管 canRotate 现在是什么
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity ?: return@onDispose
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullScreen) { fullScreen = false }

    return remember(fullScreen, isLandscape) {
        VideoFullScreenState(
            isFullScreen = fullScreen,
            isLandscape = isLandscape,
            toggle = { fullScreen = !fullScreen },
            // 旋转按钮只在全屏态出现，此处已是全屏；切到与当前相反的一侧
            rotate = {
                rotateOverride = VideoControlsPolicy.nextOrientation(currentLandscape = isLandscape)
            },
        )
    }
}

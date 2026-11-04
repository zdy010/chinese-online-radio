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
 * @param canRotate 现在是不是“能真的转”的设备（调用方传 `isCarSurface().not()`）。
 *   **没默认值**：上一版给了 `= true`，漏传不会报错也不会有任何测试能照出来，
 *   只有真机能照出来 —— 现在漏传就是编译错误。
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
fun rememberVideoFullScreen(canRotate: Boolean): VideoFullScreenState {
    val context = LocalContext.current
    // 用 LocalConfiguration 而不是 context.resources：后者不是快照态，转屏后不会重组，
    // 旋转按钮就会拿着一份过期的"当前是横屏"结论。
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var rotateOverride by rememberSaveable { mutableStateOf<VideoOrientation?>(null) }

    // canRotate 不进 key：它会在全屏期间翻转（sw≥600 设备转一下手机就换形态），
    // 一旦进了 key，用户在全屏里手动把设备扳回竖屏时，effect 会因 key 变化重跑，
    // 把「进入全屏要横屏」的请求补发一遍，直接吞掉用户手里的旋转。
    val canRotateNow by rememberUpdatedState(canRotate)

    LaunchedEffect(fullScreen, rotateOverride) {
        val activity = context as? android.app.Activity
        if (activity == null) {
            android.util.Log.w("VideoFullScreen", "上下文不是 Activity，全屏命令跳过")
            return@LaunchedEffect
        }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullScreen) {
            // 进入全屏：只有可转设备才发方向请求；用户按过旋转按钮时尊重他的选择
            if (canRotateNow) {
                // 待真机确认：模拟器虚拟传感器会压过方向请求（见 spec §5.2 已知环境限制）
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
            // 退出全屏：不受 canRotate 约束，永远复位（rotateOverride 的清理交给 toggle/返回键，
            // 不在这里写回 null——那会让 key 变化重启 effect，把同一套复位命令再发一遍）
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

    BackHandler(enabled = fullScreen) {
        rotateOverride = null
        fullScreen = false
    }

    return remember(fullScreen, isLandscape) {
        VideoFullScreenState(
            isFullScreen = fullScreen,
            isLandscape = isLandscape,
            toggle = {
                if (fullScreen) rotateOverride = null
                fullScreen = !fullScreen
            },
            // 以“上一次请求”为事实源，不拿观测到的 isLandscape：设备不理会请求时（折叠屏、
            // 分屏、部分车机 ROM，以及本项目的模拟器），观测值永远不变，按钮会“按了没反应”；
            // 转屏动画期内连点也会两下只切一下。那些都是 spec §3.1 自己立的反模式。
            rotate = {
                val current = rotateOverride ?: VideoOrientation.Landscape
                rotateOverride = VideoControlsPolicy.nextOrientation(
                    currentLandscape = current == VideoOrientation.Landscape
                )
            },
        )
    }
}

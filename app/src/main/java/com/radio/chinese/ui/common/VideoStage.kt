package com.radio.chinese.ui.common

import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.radio.chinese.service.PlayerManager

/**
 * 全站唯一的视频画面组件。
 *
 * 绑的是 `RadioService` 那个 ExoPlayer 实例（经 `VideoPlayerMount` 交出），
 * **不是** `MediaController` —— controller 调 setVideoSurface* 会抛异常。
 *
 * 尺寸一律由外部 `modifier` 决定（调用点用 `weight` 拿空间），本组件不写固定宽高：
 * 车机档给固定高度会把播控顶出 720 高的首屏，用户连暂停都按不到。
 *
 * @param placeholder 播放器还没建起时的占位（避免布局跳动），默认空。
 */
@Composable
fun VideoStage(
    playerManager: PlayerManager,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
) {
    val view = LocalView.current
    val mount = playerManager.videoPlayerMount
    val player by mount.playerFlow.collectAsState()

    // 本 BOM 的 AndroidView 不返回视图实例，所以由 factory 自己把实例交给一个 remembered 引用
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)   // 自带黑边适配，不拉伸不变形
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
            }.also { playerViewRef.value = it }
        },
        update = { pv ->
            // 只在实例真的变了才写：每次重组都调 setPlayer 会触发重新准备
            if (pv.player !== player) pv.player = player
        },
    )

    val playerView = playerViewRef.value

    // 所有权声明与闸门开关放在副作用阶段，不在 update 里写 StateFlow（那是重组期）
    if (playerView != null) {
        val pv = playerView
        DisposableEffect(pv) {
            mount.acquireStage(pv)
            playerManager.setVideoStageMounted(true)
            onDispose {
                // 被后来的 Stage 顶掉时不得去 setPlayer(null)，否则会把新页面的面摘黑
                if (mount.releaseStage(pv)) {
                    pv.player = null
                    playerManager.setVideoStageMounted(false)
                }
            }
        }
    }

    // 播放中保持屏幕常亮；离开组合时还原，避免影响电台只听声时的灭屏
    val window = (view.context as? android.app.Activity)?.window
    DisposableEffect(window, player) {
        if (player != null) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    if (player == null) placeholder()
}

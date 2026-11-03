package com.radio.chinese.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.radio.chinese.domain.model.VideoControlsPolicy
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import kotlinx.coroutines.delay

/**
 * 画面 + 控制层的唯一外壳。`VideoStage` 继续只管绑面，本层只管"要不要给入口"。
 *
 * 手势与按钮的安装条件同一条判据（`showsFullScreenButton`）：
 * 车机档非全屏时**完全不装**，画面区不吞点击，今天的行为（滚动、页面手势）一律不变；
 * 但已在全屏时必须装 —— 否则按钮 3 秒收起后再也唤不回来，只剩系统返回键一条路。
 */
@Composable
fun VideoStageFrame(
    playerManager: PlayerManager,
    fullScreen: VideoFullScreenState,
    modifier: Modifier = Modifier,
) {
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
    val interactive = VideoControlsPolicy.showsFullScreenButton(
        isCarSurface = isCarSurface(),
        hasVideoTrack = hasVideo,
        isFullScreen = fullScreen.isFullScreen,
    )

    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, fullScreen.isFullScreen, interactive) {
        if (!interactive) return@LaunchedEffect
        if (controlsVisible) {
            delay(VideoControlsPolicy.CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(modifier) {
        // matchParentSize / align 都是 BoxScope 的成员，不需要 import
        VideoStage(playerManager = playerManager, modifier = Modifier.matchParentSize())

        if (interactive) {
            // 透明手势层：点画面切换控制层。放在画面之上、按钮之下。
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } }
            )
            if (controlsVisible) {
                IconButton(
                    onClick = {
                        fullScreen.toggle()
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(Dimens.TouchMin),
                ) {
                    Icon(
                        imageVector = if (fullScreen.isFullScreen) Icons.Default.FullscreenExit
                                      else Icons.Default.Fullscreen,
                        contentDescription = if (fullScreen.isFullScreen) "退出全屏" else "全屏",
                    )
                }
            }
        }
    }
}

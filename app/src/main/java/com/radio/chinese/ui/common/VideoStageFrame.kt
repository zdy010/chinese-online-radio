package com.radio.chinese.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.radio.chinese.domain.model.VideoControlsPolicy
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.theme.Dimens

/**
 * 画面 + 控制层的唯一外壳。`VideoStage` 继续只管绑面，本层只管"要不要给入口"。
 *
 * 入口判据不再看形态（本轮 bug 的根因）：只要页面有画面就给全屏入口，
 * 车机上"全屏"的含义是画面独占整块面板；旋转按钮则只在"手机 + 已在全屏"时给，
 * 因为车机恒横屏，给了就是"按了没反应"。
 *
 * 控制层**常驻**（不再 3 秒自动收起）：上一版定时收起，而"点画面才能唤出"
 * 没有任何提示，不知道要点的人看到的就是"这个 App 没有全屏功能"。
 * 单击用 detectTapGestures：超过 touch slop 后由父级滚动容器接管，因此不抢页面滚动
 * —— 这条由验收 F11 实测守住，不靠推理。
 */
@Composable
fun VideoStageFrame(
    playerManager: PlayerManager,
    fullScreen: VideoFullScreenState,
    modifier: Modifier = Modifier,
) {
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
    val interactive = VideoControlsPolicy.showsFullScreenButton(
        hasVideoTrack = hasVideo,
        isFullScreen = fullScreen.isFullScreen,
    )
    val showRotate = VideoControlsPolicy.showsRotateButton(
        // 用进入全屏那一刻的判定，不用当前形态：大屏手机一进全屏就横屏、
        // isCarSurface() 会当场翻成 true，按当前形态判按钮会在用户眼前消失。
        canRotateAtEntry = fullScreen.canRotateAtEntry,
        isFullScreen = fullScreen.isFullScreen,
    )

    var controlsVisible by remember { mutableStateOf(true) }

    Box(modifier) {
        // matchParentSize 与 align 是 BoxScope 成员（不用 import）；offset 是 Modifier 扩展，已在顶部 import
        VideoStage(playerManager = playerManager, modifier = Modifier.matchParentSize())

        if (interactive) {
            // 透明手势层：点画面切换控制层。放在画面之上、按钮之下。
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } }
            )
            if (controlsVisible) {
                if (showRotate) {
                    IconButton(
                        onClick = {
                            fullScreen.rotate()
                            controlsVisible = true
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = -Dimens.TouchMin)   // 与全屏按钮并排，不重叠
                            .size(Dimens.TouchMin),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "切换横竖屏",
                        )
                    }
                }
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

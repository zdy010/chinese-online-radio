package com.radio.chinese.ui.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.ui.common.VideoFullScreenState
import com.radio.chinese.ui.theme.isTvSurface
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.IptvBrief
import com.radio.chinese.domain.model.StationDisplayName
import com.radio.chinese.domain.model.VideoTrackGate
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.VideoStageFrame
import com.radio.chinese.ui.common.rememberVideoFullScreen
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

/**
 * iptv 专用播放页（spec V6 / §7.3）。
 *
 * 与 OperaPlayerScreen 同构：无参数路由，播放状态一律读 PlayerManager，不自建 ViewModel。
 * 画面、全屏、旋转、控制层常驻、方向复位全部由 VideoStageFrame +
 * rememberVideoFullScreen 白拿，本页只负责显式开闸门、频道信息、单行播控与源切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvPlayerScreen(
    playerManager: PlayerManager,
    onNavigateBack: () -> Unit,
    viewModel: IptvPlayerViewModel = hiltViewModel(),
) {
    val station by playerManager.currentStation.collectAsState()
    val isPlaying by playerManager.isPlaying.collectAsState()
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
    val currentSource by playerManager.currentSource.collectAsState()
    val sourceScores by playerManager.sourceScores.collectAsState()
    val status by playerManager.status.collectAsState()
    val error by playerManager.error.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()

    // 电视进播放页就是全屏，不给「要不要全屏」这一步，也不给全屏/旋转按钮。
    // 锁定后返回键直接退回列表（不会被「先退全屏」吞掉），媒体键仍由会话处理。
    val tv = isTvSurface()
    val fullScreen = rememberVideoFullScreen(
        canRotate = isCarSurface().not(),
        initiallyFullScreen = tv,
        lockFullScreen = tv,
    )

    // 闸门跟「页面意图」：本页开着就一直要画面，**不跟 hasVideoTrack**。
    // playStation 每次都把 hasVideo 置 false（要等新流的 onTracksChanged），绑它的话
    // 每次切台都会先收闸再开闸，两次重写 trackSelectionParameters 打断音频 ——
    // 等于把 2.2.4 回退掉的问题从「返回列表」搬到了「切台瞬间」（QA 审查 P0）。
    // 漏这句解码器不会开视频轨，症状是「iptv 永远只有声音没画面」。
    // 离开本页不收闸：重写参数会打断后台音频、让画面拖着渐隐才消失；
    // 收闸交给 MainScreen 在真正离开 iptv 路由时做。
    LaunchedEffect(Unit) {
        playerManager.setVideoPictureWanted(
            VideoTrackGate.pictureWantedForIptvPlayer(pageOpen = true)
        )
    }

    // 上下台与方控滚轮读**同一个**基准集合（V11）：这个集合由 iptv 页注册，
    // 电台页不注册时为空，播放页不会自己另算一份电台列表。
    val baseState by playerManager.skipBaseFlow.collectAsState()
    val base = baseState.orEmpty()
    val index = base.indexOfFirst { it.id == station?.id }
    var showSources by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = if (fullScreen.isFullScreen) WindowInsets(0) else NestedWindowInsets,
        topBar = {
            // 全屏时顶栏整块不渲染，退出入口交给画面控制层（与 PlayerScreen 一致）
            if (!fullScreen.isFullScreen) {
                TopAppBar(
                    windowInsets = NestedWindowInsets,
                    modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                    title = {
                        Text(
                            text = station?.let { StationDisplayName.of(it) } ?: "iptv",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        val id = station?.id
                        val fav = id != null && id in favoriteIds
                        IconButton(
                            onClick = { id?.let(viewModel::toggleFavorite) },
                            enabled = id != null,
                        ) {
                            Icon(
                                imageVector = if (fav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (fav) "取消收藏" else "收藏",
                                tint = if (fav) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (tv) {
            // 电视：画面铺满全屏，节目信息浮在画面下沿，没有顶栏/播控/全屏按钮
            TvFullScreenStage(
                playerManager = playerManager,
                fullScreen = fullScreen,
                hasVideo = hasVideo,
                status = status,
                error = error,
                number = if (index >= 0) index + 1 else 0,
                total = base.size,
                quality = currentSource?.quality.orEmpty(),
                brief = station?.let { IptvBrief.of(it) }.orEmpty(),
                base = base,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 黑底：PlayerView 是 RESIZE_MODE_FIT，16:9 内容在竖屏里上下留边；
                    // 留边露出 Scaffold 的浅色 surface 会像"画面悬浮在页面中间"，黑底才是播放器观感。
                    .background(Color.Black),
            ) {
                VideoStageFrame(
                    playerManager = playerManager,
                    fullScreen = fullScreen,
                    modifier = Modifier.fillMaxSize(),
                    // iptv 页恒给全屏入口：流没起来时用户也要能找到它
                    alwaysShowFullScreen = true,
                )
                // 还没出画面时给个明确反馈：否则整块纯黑只有底部一行字，像卡死
                if (!hasVideo && status != null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(Dimens.GapMedium))
                        Text(
                            text = status.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                    }
                }
            }

            // 全屏时画面独占：退出入口在 VideoStageFrame 的控制层上
            if (!fullScreen.isFullScreen) {
                IptvInfoRow(
                    number = if (index >= 0) index + 1 else 0,
                    total = base.size,
                    quality = currentSource?.quality.orEmpty(),
                    brief = station?.let { IptvBrief.of(it) }.orEmpty(),
                    message = error ?: status,
                    isError = error != null,
                )

                // 播控单行（项目红线）：上一台 / 播放暂停 / 下一台 / 源切换，任何形态都不拆行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { playerManager.playPrevious(base) },
                        modifier = Modifier.size(Dimens.TouchMin),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一个频道",
                            modifier = Modifier.size(Dimens.IconLarge),
                        )
                    }
                    FilledIconButton(
                        onClick = { playerManager.togglePlayPause() },
                        modifier = Modifier.size(Dimens.PlayButtonSize),
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停播放" else "继续播放",
                            modifier = Modifier.size(Dimens.PlayIconSize),
                        )
                    }
                    IconButton(
                        onClick = { playerManager.playNext(base) },
                        modifier = Modifier.size(Dimens.TouchMin),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一个频道",
                            modifier = Modifier.size(Dimens.IconLarge),
                        )
                    }
                    // 源切换用下拉：菜单浮在按钮上，不把控件行拆成两行
                    Box {
                        IconButton(
                            onClick = { showSources = true },
                            // 轮转队列还没建好时别弹一个空菜单：那表现为"点了没反应"
                            enabled = sourceScores.isNotEmpty(),
                            modifier = Modifier.size(Dimens.TouchMin),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = "节目源",
                                modifier = Modifier.size(Dimens.IconLarge),
                            )
                        }
                        DropdownMenu(expanded = showSources, onDismissRequest = { showSources = false }) {
                            sourceScores.forEachIndexed { i, (src, _) ->
                                DropdownMenuItem(
                                    // label 一律为空（spec §2.3）：序号 + quality 才是用户该看的
                                    text = {
                                        Text("源 ${i + 1}" + if (src.quality.isNotBlank()) " · ${src.quality}" else "")
                                    },
                                    onClick = {
                                        showSources = false
                                        playerManager.switchToSource(src.url)
                                    },
                                    enabled = src.url != currentSource?.url,
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun IptvInfoRow(
    number: Int,
    total: Int,
    quality: String,
    brief: String,
    message: String?,
    isError: Boolean,
    modifier: Modifier = Modifier,
    /** 叠在画面上时：半透明黑底 + 白字，保证任何画面内容下都可读 */
    onPicture: Boolean = false,
) {
    val labelColor = if (onPicture) Color.White.copy(alpha = 0.78f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = if (onPicture) Color.White else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .then(if (onPicture) Modifier.background(Color.Black.copy(alpha = 0.62f)) else Modifier)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall)
    ) {
        // total=0：基准还没注册；number=0：当前台不在当前页列表里（例如从九键候选选中了
        // 一个境外台，而基准只有中国台）。两种情况显示「第 0 / …」都是误导，直接不显示。
        if (total > 0 && number > 0) {
            Text(
                text = "第 $number / $total 频道",
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
            )
        }
        // 简介：播放页可以多行，不滚动（V14）
        if (brief.isNotBlank()) {
            Text(
                text = brief,
                style = MaterialTheme.typography.bodyMedium,
                color = bodyColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (quality.isNotBlank()) {
            Text(
                text = "清晰度 $quality",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 电视全屏画面：视频铺满整屏，节目信息浮在下沿，没有任何按钮。
 *
 * 遥控器：左右切台、OK/确定 暂停继续。媒体键（上一台/下一台/播放暂停）
 * 仍由 MainActivity + MediaSession 处理，这里不重复接管，避免一次按键切两台。
 */
@Composable
private fun TvFullScreenStage(
    playerManager: PlayerManager,
    fullScreen: VideoFullScreenState,
    hasVideo: Boolean,
    status: String?,
    error: String?,
    number: Int,
    total: Int,
    quality: String,
    brief: String,
    base: List<RadioStation>,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                // 长按左右键会连发 repeat：每次切一台，按住一秒能跳过十几台
                if (native.repeatCount != 0) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        playerManager.playPrevious(base)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        playerManager.playNext(base)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        playerManager.togglePlayPause()
                        true
                    }
                    else -> false
                }
            }
    ) {
        VideoStageFrame(
            playerManager = playerManager,
            fullScreen = fullScreen,
            modifier = Modifier.fillMaxSize(),
            showControls = false,
        )
        if (!hasVideo && status != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(Dimens.GapMedium))
                Text(
                    text = status.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
        IptvInfoRow(
            number = number,
            total = total,
            quality = quality,
            brief = brief,
            message = error ?: status,
            isError = error != null,
            onPicture = true,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
    }
}

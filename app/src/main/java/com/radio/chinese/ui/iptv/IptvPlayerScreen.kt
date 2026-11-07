package com.radio.chinese.ui.iptv

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
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

    val fullScreen = rememberVideoFullScreen(canRotate = isCarSurface().not())

    // 闸门跟页面意图，不跟 View 生命周期（spec §7.3）。漏这句解码器不会开视频轨，
    // 症状是"iptv 永远只有声音没画面"，且编译与单测都抓不到。
    LaunchedEffect(hasVideo) { playerManager.setVideoPictureWanted(hasVideo) }
    DisposableEffect(Unit) {
        onDispose { playerManager.setVideoPictureWanted(false) }
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
                            text = station?.name ?: "iptv",
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

@Composable
private fun IptvInfoRow(
    number: Int,
    total: Int,
    quality: String,
    message: String?,
    isError: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall)) {
        Text(
            text = "第 $number / $total 频道",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (quality.isNotBlank()) {
            Text(
                text = "清晰度 $quality",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

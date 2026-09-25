package com.radio.chinese.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.radio.chinese.data.local.availabilityToColor
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.StationCover
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    stationId: String,
    onNavigateBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val timerActive by viewModel.sleepTimer.isActive.collectAsState()
    val timerRemaining by viewModel.sleepTimer.remainingSeconds.collectAsState()
    var showTimerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(stationId) {
        viewModel.loadStation(stationId)
    }

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                // 顶栏默认 64dp 高，不把顶栏本身抬到 88dp，里面的按钮就只能拿到 64dp 短边
                modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 收藏放在顶栏：整页可滚动后，原先贴在正文末尾的收藏按钮
                    // 在默认字号下就已经被挤到屏外，大字号/横屏彻底看不到。
                    val actionSize = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                    IconButton(
                        onClick = { viewModel.toggleFavorite() },
                        modifier = actionSize
                    ) {
                        Icon(
                            if (uiState.isFavorite) Icons.Default.Favorite
                            else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (uiState.isFavorite) "取消收藏" else "收藏",
                            tint = if (uiState.isFavorite) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { showTimerDialog = true },
                        modifier = actionSize
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = "睡眠定时器",
                            tint = if (timerActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        val station = uiState.station

        if (station == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                LoadingState(text = "正在打开电台…")
            }
        } else {
            // 封面尺寸按视口高度收着给：原先 fillMaxWidth + aspectRatio(1f) 会让封面
            // 跟着屏宽走，横屏时撑出 1000dp 高的圆，播放/暂停键被完全顶出屏幕。
            // 横屏那一屏只有 390dp 高，连上下留白都要跟着收紧。
            val viewportHeight = LocalConfiguration.current.screenHeightDp.dp
            val shortViewport = viewportHeight < 500.dp
            val coverSize = if (shortViewport) Dimens.CoverSmall
            else minOf(Dimens.CoverLargeMax, viewportHeight * 0.30f)
            val pageVerticalPadding = if (shortViewport) Dimens.GapMedium else Dimens.GapXLarge
            val gapAfterCover = if (shortViewport) Dimens.GapSmall else Dimens.GapHuge
            val gapBeforeControls = if (shortViewport) Dimens.GapSmall else Dimens.GapXLarge

            // 可滚动：此前整页没有任何滚动容器，横屏或系统大字号下
            // 多个节目源会把播放/暂停键顶出屏幕，用户连暂停都做不到。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.GapHuge, vertical = pageVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 车机横屏：封面与台名/频率/简介左右并排，把竖向空间留给下面的播控；
                // 手机档仍按原来的顺序铺开，间距与取值一字不改。
                if (isCarSurface()) {
                    Row(
                        // 超宽屏上不让内容从左拉到右；widthIn 必须在 fillMaxWidth 之前
                        modifier = Modifier
                            .widthIn(max = Dimens.CarContentMaxWidth)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GapXLarge),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 封面包在居中容器里：直接把 weight 传给 StationCover 会把它
                        // 里的 .size() 撑成一条胶囊（weight 定宽不可被内部缩小）
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CoverBlock(
                                url = station.logoUrl,
                                name = station.name,
                                isPlaying = uiState.isPlaying,
                                size = coverSize
                            )
                        }
                        StationInfoBlock(
                            station = station,
                            showDescription = !shortViewport,
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                } else {
                    CoverBlock(
                        url = station.logoUrl,
                        name = station.name,
                        isPlaying = uiState.isPlaying,
                        size = coverSize
                    )
                    Spacer(modifier = Modifier.height(gapAfterCover))
                    StationInfoBlock(
                        station = station,
                        showDescription = !shortViewport,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.GapMedium))

                // Playback Status Indicator
                PlaybackStatusIndicator(
                    playbackState = uiState.playbackState,
                    isPlaying = uiState.isPlaying,
                    error = uiState.error,
                    status = uiState.status
                )

                // Source Switching
                if (uiState.sourceScores.size > 1) {
                    Spacer(modifier = Modifier.height(Dimens.GapMedium))
                    SourceSelector(
                        sources = uiState.sourceScores,
                        currentSource = uiState.currentSource,
                        onSwitch = { viewModel.switchToSource(it.url) }
                    )
                }

                Spacer(modifier = Modifier.height(gapBeforeControls))

                // Playback Controls
                Row(
                    modifier = Modifier
                        .then(
                            if (isCarSurface()) Modifier.widthIn(max = Dimens.CarContentMaxWidth)
                            else Modifier
                        )
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    IconButton(
                        onClick = { viewModel.playPrevious() },
                        modifier = Modifier.size(Dimens.TouchMin)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一个电台",
                            modifier = Modifier.size(Dimens.IconLarge)
                        )
                    }

                    // Play/Pause
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(Dimens.PlayButtonSize),
                        shape = CircleShape
                    ) {
                        Icon(
                            if (uiState.isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "暂停播放" else "继续播放",
                            modifier = Modifier.size(Dimens.PlayIconSize)
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { viewModel.playNext() },
                        modifier = Modifier.size(Dimens.TouchMin)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一个电台",
                            modifier = Modifier.size(Dimens.IconLarge)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.GapLarge))

                // Sleep Timer Indicator
                if (timerActive) {
                    Spacer(modifier = Modifier.height(Dimens.GapLarge))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "定时关闭 ${formatTime(timerRemaining)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { viewModel.cancelSleepTimer() },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("取消", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.GapSmall))
            }
        }
    }

    // Sleep Timer Dialog
    if (showTimerDialog) {
        SleepTimerDialog(
            onDismiss = { showTimerDialog = false },
            onSelectMinutes = { minutes ->
                viewModel.startSleepTimer(minutes)
                showTimerDialog = false
            },
            isActive = timerActive,
            onCancel = {
                viewModel.cancelSleepTimer()
                showTimerDialog = false
            }
        )
    }
}

@Composable
private fun CoverBlock(
    url: String,
    name: String,
    isPlaying: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    // 只在真正播放时才跑脉冲动画，暂停后不再 60fps 空转
    val pulseScale = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        ).value
    } else 1f

    StationCover(
        url = url,
        size = size,
        label = name,
        modifier = modifier.scale(pulseScale)
    )
}

/** 台名 / 频率 / 简介。车机档放在封面右侧，手机档放在封面下方。 */
@Composable
private fun StationInfoBlock(
    station: RadioStation,
    showDescription: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = station.name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        if (station.frequency.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Dimens.GapTiny))
            Text(
                text = station.frequency,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        // 视口不够高时让位给播放键：宁可少一段可有可无的简介，也不能让暂停按不到
        if (showDescription && station.description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Dimens.GapSmall))
            Text(
                text = station.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun SourceSelector(
    sources: List<Pair<StationSource, Float>>,
    currentSource: StationSource?,
    onSwitch: (StationSource) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.SourceListMax)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "节目源切换",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Dimens.GapSmall))
        sources.forEach { (source, score) ->
            val isActive = currentSource?.url == source.url
            val colorInt = availabilityToColor(score)
            val color = Color(
                ((colorInt shr 16) and 0xFF) / 255f,
                ((colorInt shr 8) and 0xFF) / 255f,
                (colorInt and 0xFF) / 255f
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.GapTiny)
                    .clickable(enabled = !isActive) { onSwitch(source) },
                shape = MaterialTheme.shapes.small,
                // 不再用 color.copy(alpha = 0.15f)：透明堆叠在深色模式下会发灰、对比度不足
                color = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                border = if (isActive) CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(color)
                ) else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Dimens.GapMedium, vertical = Dimens.GapSmall),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 颜色指示圆点
                    Box(
                        modifier = Modifier
                            .size(Dimens.GapSmall)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(Dimens.GapSmall))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.label.ifEmpty {
                                val idx = sources.indexOfFirst { it.first.url == source.url }
                                if (idx >= 0) "源 ${idx + 1}" else "源"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                        if (source.bitrate > 0) {
                            Text(
                                text = "${source.bitrate}kbps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isActive) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    } else {
                        Text(
                            text = "%.0f%%".format(score * 100),
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackStatusIndicator(
    playbackState: Int,
    isPlaying: Boolean,
    error: String?,
    status: String?
) {
    // 显示错误（优先级最高）：现在只承载真正的失败，进度提示走 status 中性态
    if (error != null) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Dimens.GapLarge, vertical = Dimens.GapSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(Dimens.IconSmall)
                )
                Spacer(modifier = Modifier.width(Dimens.GapSmall))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        return
    }

    // 显示播放状态
    val (text, icon, container, content) = when {
        !status.isNullOrEmpty() -> Quad(
            status,
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        playbackState == Player.STATE_BUFFERING -> Quad(
            "缓冲中…",
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        playbackState == Player.STATE_READY && isPlaying -> Quad(
            "正在播放",
            Icons.Default.PlayCircle,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        playbackState == Player.STATE_READY -> Quad(
            "已暂停",
            Icons.Default.PauseCircle,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        playbackState == Player.STATE_IDLE -> Quad(
            "准备中",
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Quad(
            "",
            null,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (text.isEmpty()) return

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.GapLarge, vertical = Dimens.GapSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(Dimens.IconSmall)
                )
                Spacer(modifier = Modifier.width(Dimens.GapSmall))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = content
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onSelectMinutes: (Int) -> Unit,
    isActive: Boolean,
    onCancel: () -> Unit
) {
    val options = listOf(15, 30, 45, 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("选择定时关闭时间：")
                Spacer(modifier = Modifier.height(Dimens.GapLarge))
                options.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.GapTiny),
                    ) {
                        OutlinedButton(
                            onClick = { onSelectMinutes(minutes) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("$minutes 分钟")
                        }
                    }
                }
                if (isActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("取消定时器")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun formatTime(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
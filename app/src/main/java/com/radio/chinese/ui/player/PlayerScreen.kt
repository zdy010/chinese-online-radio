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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.radio.chinese.data.local.availabilityToColor
import com.radio.chinese.domain.model.StationSource

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
        topBar = {
            TopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showTimerDialog = true }) {
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
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // Station Logo with animation when playing
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (uiState.isPlaying) 1.05f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                AsyncImage(
                    model = station.logoUrl.ifEmpty { null },
                    contentDescription = station.name,
                    modifier = Modifier
                        .size(200.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Station Name
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )

                // Frequency
                if (station.frequency.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = station.frequency,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                // Description
                if (station.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = station.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Playback Status Indicator
                PlaybackStatusIndicator(
                    playbackState = uiState.playbackState,
                    isPlaying = uiState.isPlaying,
                    error = uiState.error
                )

                // Source Switching
                if (uiState.sourceScores.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SourceSelector(
                        sources = uiState.sourceScores,
                        currentSource = uiState.currentSource,
                        onSwitch = { viewModel.switchToSource(it.url) }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    IconButton(
                        onClick = { viewModel.playPrevious() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一个",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Play/Pause
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            if (uiState.isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { viewModel.playNext() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一个",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Favorite and Info Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Favorite
                    TextButton(
                        onClick = { viewModel.toggleFavorite() }
                    ) {
                        Icon(
                            if (uiState.isFavorite) Icons.Default.Favorite
                            else Icons.Outlined.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (uiState.isFavorite) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (uiState.isFavorite) "已收藏" else "收藏")
                    }
                }

                // Sleep Timer Indicator
                if (timerActive) {
                    Spacer(modifier = Modifier.height(16.dp))
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

                Spacer(modifier = Modifier.height(32.dp))
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
private fun SourceSelector(
    sources: List<Pair<StationSource, Float>>,
    currentSource: StationSource?,
    onSwitch: (StationSource) -> Unit
) {
    Column {
        Text(
            text = "节目源切换",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
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
                    .padding(vertical = 2.dp)
                    .clickable(enabled = !isActive) { onSwitch(source) },
                shape = RoundedCornerShape(8.dp),
                color = if (isActive) color.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surface,
                border = if (isActive) CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(color)
                ) else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 颜色指示圆点
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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
    error: String?
) {
    // 显示错误（优先级最高）
    if (error != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        return
    }

    // 显示播放状态
    val (text, icon, color) = when (playbackState) {
        Player.STATE_BUFFERING -> Triple(
            "缓冲中…",
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.tertiary
        )
        Player.STATE_READY -> if (isPlaying) Triple(
            "正在播放",
            Icons.Default.PlayCircle,
            MaterialTheme.colorScheme.primary
        ) else Triple(
            "已暂停",
            Icons.Default.PauseCircle,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        Player.STATE_IDLE -> Triple(
            "连接中…",
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Triple("", null, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

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
            Column {
                Text("选择定时关闭时间：")
                Spacer(modifier = Modifier.height(16.dp))
                options.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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

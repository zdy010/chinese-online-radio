package com.radio.chinese.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.MiniPlayerShape
import com.radio.chinese.ui.theme.isCarSurface

/**
 * 统一的迷你播放条。
 *
 * 此前有两个同名 MiniPlayerBar、形态完全不同：电台版通栏直角 72dp，戏曲版悬浮圆角卡条约 170dp
 * 且用 overlay 压在列表上，导致列表最后一项永久被盖住。这里共用一个外壳，
 * 并一律由 Scaffold.bottomBar 承载，让内容区自动让位。
 */

@Composable
private fun MiniPlayerShell(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        ClickableShell(onClick = onClick, modifier = modifier, content = content)
    } else {
        PlainShell(modifier = modifier, content = content)
    }
}

@Composable
private fun ClickableShell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MiniPlayerShape,
        tonalElevation = Dimens.GapSmall,
        shadowElevation = Dimens.GapMedium,
        onClick = onClick
    ) {
        Column(content = content)
    }
}

@Composable
private fun PlainShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MiniPlayerShape,
        tonalElevation = Dimens.GapSmall,
        shadowElevation = Dimens.GapMedium
    ) {
        Column(content = content)
    }
}

/** 电台版：单行，高 [Dimens.MiniPlayerHeight]。 */
@Composable
fun RadioMiniPlayerBar(
    title: String,
    subtitle: String,
    coverUrl: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MiniPlayerShell(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.MiniPlayerHeight - Dimens.GapLarge)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StationCover(url = coverUrl, size = Dimens.CoverTiny, label = title)
            Spacer(modifier = Modifier.width(Dimens.GapMedium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(Dimens.TouchMin)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停播放" else "开始播放"
                )
            }
        }
    }
}

/**
 * 戏曲版：进度滑条 + 信息行 + 控件行。
 *
 * 本应用没有戏曲的全屏播放页，这条播放条是唯一控制面：上一首/下一首/停止/循环
 * 以及**可拖动的进度条**都是必需能力，不能为了“统一形态”把它们缩掉。
 * 电台版是直播流，本来就无需 seek，所以只有它是单行矮条。
 */
@Composable
fun OperaMiniPlayerBar(
    title: String,
    timeText: String,
    bitrateText: String?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    repeatLabel: String,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSeek = durationMs > 0L
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }
    val car = isCarSurface()

    MiniPlayerShell(modifier = modifier) {
        // canSeek 为假（直播流 / 时长未知）时滑条与 ±15 秒都不出现，
        // 避免把进度当成 0 拖回去。
        if (canSeek) {
            Slider(
                value = (if (isDragging) dragPositionMs else positionMs).toFloat(),
                onValueChange = {
                    dragPositionMs = it.toLong()
                    isDragging = true
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek(dragPositionMs)
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    // 手机档保持原来的 32dp 下限，车机档抬到 88dp 命中区
                    .heightIn(min = if (car) Dimens.TouchMin else Dimens.GapHuge)
                    .padding(horizontal = Dimens.GapMedium),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding)
                .height(Dimens.RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.CoverTiny)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconMedium),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(Dimens.GapMedium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (bitrateText != null) "$timeText · $bitrateText" else timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "停止播放"
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapTiny),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCycleRepeat,
                contentPadding = PaddingValues(
                    horizontal = Dimens.GapSmall,
                    vertical = 0.dp
                )
            ) {
                Text(
                    text = repeatLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // ±15 秒：行车中拖细滑条不现实，这两个按钮是主要纠偏手段。
            // 只加不减——滑条依旧在，不拿走任何既有能力。
            if (car) {
                IconButton(onClick = onSeekBackward, modifier = Modifier.size(Dimens.TouchMin)) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "后退 15 秒",
                        modifier = Modifier.size(Dimens.IconLarge)
                    )
                }
            }
            IconButton(onClick = onPrevious, modifier = if (car) Modifier.size(Dimens.TouchMin) else Modifier) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一段",
                    modifier = Modifier.size(Dimens.IconMedium)
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = if (car) Modifier.size(Dimens.TouchMin) else Modifier
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停播放" else "继续播放",
                    modifier = Modifier.size(Dimens.IconLarge)
                )
            }
            IconButton(onClick = onNext, modifier = if (car) Modifier.size(Dimens.TouchMin) else Modifier) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一段",
                    modifier = Modifier.size(Dimens.IconMedium)
                )
            }
            if (car) {
                IconButton(onClick = onSeekForward, modifier = Modifier.size(Dimens.TouchMin)) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = "前进 15 秒",
                        modifier = Modifier.size(Dimens.IconLarge)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

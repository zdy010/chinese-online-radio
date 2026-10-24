package com.radio.chinese.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
                modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
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
 * 戏曲版：只显示正在播放的名称。
 *
 * 用户实测要求迷你条再降高度——不放进度条、不放任何控制按钮，只留“在放什么”。
 * 整条可点，点进去就是全屏播放页（进度、±10、上下段、循环、停止、倍速全在那里）。
 * 因此这里刻意保持最小：封面 + 曲名一行，通栏高度与电台版一致。
 */
@Composable
fun OperaMiniPlayerBar(
    title: String,
    onClick: (() -> Unit)? = null,
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

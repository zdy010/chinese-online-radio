package com.radio.chinese.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.ErrorState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.theme.Dimens

@Composable
fun BrowseScreen(
    items: List<AudioTrack>,
    isLoading: Boolean,
    error: String?,
    onItemClick: (AudioTrack) -> Unit,
    onRefresh: () -> Unit,
    onToggleFavorite: (AudioTrack) -> Unit = {},
    isFavorited: (String) -> Boolean = { false }
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            error != null && items.isEmpty() -> {
                ErrorState(
                    message = error,
                    hint = "目录可能已移动，或网盘授权已过期",
                    onRetry = onRefresh
                )
            }

            items.isEmpty() && isLoading -> {
                LoadingState(text = "正在读取目录…")
            }

            items.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "该目录下暂无内容",
                    hint = "回到上一层看看，或点右上角刷新"
                )
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
                ) {
                    // 必须给 key：BrowseItem 内部的收藏态是 remember 的，
                    // 没 key 时列表重排会把星标状态串到别的行上。
                    items(items, key = { it.path }) { item ->
                        BrowseItem(
                            item = item,
                            onClick = { onItemClick(item) },
                            isFav = isFavorited(item.path),
                            onToggleFav = { onToggleFavorite(item) }
                        )
                    }
                }
            }
        }

        // 已有内容时再拉一次：用顶部细进度条而不是盖住列表的大转圈
        if (isLoading && items.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BrowseItem(item: AudioTrack, onClick: () -> Unit, isFav: Boolean, onToggleFav: () -> Unit) {
    var favState by remember { mutableStateOf(isFav) }
    // 外部状态变化时同步进来
    LaunchedEffect(isFav) { favState = isFav }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.RowMinHeight)
            .padding(vertical = Dimens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isFolder) Icons.Default.Folder else Icons.Default.Audiotrack,
            contentDescription = null,
            tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconLarge)
        )
        Spacer(modifier = Modifier.width(Dimens.GapLarge))
        Column(modifier = Modifier.weight(1f)) {
            // 不再用 MarqueeText(enabled = false)：拿不到滚动收益却仍付两次 subcompose
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.isFolder && item.size > 0) {
                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.isHlsStream) {
                Text("HLS 直播流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (!item.isFolder) {
            IconButton(onClick = { favState = !favState; onToggleFav() }) {
                Icon(
                    if (favState) Icons.Default.Star else Icons.Default.StarOutline,
                    contentDescription = if (favState) "取消收藏" else "收藏这一曲",
                    tint = if (favState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (item.isFolder) {
            Icon(Icons.Default.ChevronRight, contentDescription = "进入目录", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

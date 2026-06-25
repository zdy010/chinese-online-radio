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
import androidx.compose.ui.unit.dp
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.ui.common.MarqueeText

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
        if (items.isEmpty() && !isLoading) {
            Text(
                "该目录下暂无内容",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(items) { item ->
                    BrowseItem(
                        item = item,
                        onClick = { onItemClick(item) },
                        isFav = isFavorited(item.path),
                        onToggleFav = { onToggleFavorite(item) }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (error != null) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onRefresh) { Text("重试") } }
            ) {
                Text(error)
            }
        }
    }
}

@Composable
private fun BrowseItem(item: AudioTrack, onClick: () -> Unit, isFav: Boolean, onToggleFav: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isFolder) Icons.Default.Folder else Icons.Default.Audiotrack,
            contentDescription = null,
            tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(item.name, style = MaterialTheme.typography.bodyLarge, enabled = false, modifier = Modifier.fillMaxWidth())
            if (!item.isFolder && item.size > 0) {
                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.isHlsStream) {
                Text("HLS 直播流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (!item.isFolder) {
            IconButton(onClick = onToggleFav) {
                Icon(
                    if (isFav) Icons.Default.Star else Icons.Default.StarOutline,
                    contentDescription = if (isFav) "取消收藏" else "收藏",
                    tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (item.isFolder) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

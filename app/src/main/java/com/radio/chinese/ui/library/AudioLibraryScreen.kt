package com.radio.chinese.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.data.entity.AudioFavoriteEntity
import com.radio.chinese.data.entity.AudioRecentEntity
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.ui.common.MarqueeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: AudioLibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.browsingSource != null) uiState.browsingSource!!.name else "我的音频库") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.browsingSource != null) viewModel.browseBack() else onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.browsingSource != null) {
                        IconButton(onClick = { viewModel.refreshBrowse() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    } else {
                        IconButton(onClick = { viewModel.showAddDialog() }) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.browsingSource != null) {
                // 浏览模式
                BrowseScreen(
                    sourceName = uiState.browsingSource!!.name,
                    items = uiState.browseItems,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    canGoBack = uiState.browseHistory.isNotEmpty(),
                    onItemClick = { item ->
                        if (item.isFolder) viewModel.browseFolder(item.path)
                        else viewModel.playTrack(item)
                    },
                    onBack = { viewModel.browseBack() },
                    onRefresh = { viewModel.refreshBrowse() },
                    onToggleFavorite = { item ->
                        viewModel.toggleFavorite(item.path, item.name, uiState.browsingSource?.name ?: "")
                    },
                    isFavorited = { path -> viewModel.isFavorited(path) }
                )
            } else if (uiState.sources.isEmpty() && !uiState.isLoading) {
                // 空状态
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("暂无音频库", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击右上角 + 添加音频来源\n支持本地、WebDAV、M3U 播放列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (uiState.showFavorites) {
                FavoritesList(
                    favorites = uiState.favorites,
                    onPlay = { fav -> viewModel.playFavorite(fav) },
                    onRemove = { path -> viewModel.toggleFavorite(path, "", "") }
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 收藏快捷入口
                    if (uiState.favorites.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.showFavorites() },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text("收藏 (${uiState.favorites.size})", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                    // 最近播放
                    if (uiState.recentPlays.isNotEmpty()) {
                        item { Text("最近播放", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                        items(uiState.recentPlays.take(5)) { recent ->
                            RecentPlayItem(recent = recent, onPlay = { viewModel.playRecent(recent) })
                        }
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                    // 音频库列表
                    item { Text("音频库", style = MaterialTheme.typography.titleSmall) }
                    items(uiState.sources) { source ->
                        SourceCard(source = source, onClick = { viewModel.browseSource(source) }, onDelete = { viewModel.deleteSource(source.id) })
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // MiniPlayer 播放条
            if (uiState.isPlaying && uiState.currentTrack != null) {
                MiniPlayerBar(
                    track = uiState.currentTrack!!,
                    isPlaying = uiState.isPlaying,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onStop = { viewModel.stopPlayback() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    // 添加弹窗
    if (uiState.showAddDialog) {
        AddSourceDialog(
            isLoading = uiState.isLoading,
            error = uiState.error,
            onDismiss = { viewModel.hideAddDialog() },
            onConfirm = { name, type, url, username, password ->
                viewModel.addSource(name, type, url, username, password)
            }
        )
    }
}

@Composable
fun SourceCard(source: AudioSource, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (source.type) {
                    SourceType.LOCAL -> Icons.Default.PhoneAndroid
                    SourceType.WEBDAV -> Icons.Default.Cloud
                    SourceType.M3U -> Icons.Default.List
                    SourceType.HTTP -> Icons.Default.Link
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(source.name, style = MaterialTheme.typography.titleSmall, enabled = false, modifier = Modifier.fillMaxWidth())
                Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    source.type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除音频库") },
            text = { Text("确定要删除「${source.name}」吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun FavoritesList(favorites: List<AudioFavoriteEntity>, onPlay: (AudioFavoriteEntity) -> Unit, onRemove: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(favorites) { fav ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onPlay(fav) }, shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        MarqueeText(fav.trackName, style = MaterialTheme.typography.bodyLarge, enabled = true, modifier = Modifier.fillMaxWidth())
                        Text(fav.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onRemove(fav.trackPath) }) {
                        Icon(Icons.Default.Delete, "取消收藏", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun RecentPlayItem(recent: AudioRecentEntity, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MarqueeText(recent.trackName, style = MaterialTheme.typography.bodyMedium, enabled = false, modifier = Modifier.fillMaxWidth())
            Text(recent.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MiniPlayerBar(
    track: com.radio.chinese.domain.AudioTrack,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Audiotrack, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MarqueeText(track.name, style = MaterialTheme.typography.titleSmall, enabled = true, modifier = Modifier.fillMaxWidth())
                Text(
                    formatPlaybackTime(positionMs, durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
                        modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp),
                    )
                }
            }
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放"
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Close, "停止")
            }
        }
    }
}

private fun formatPlaybackTime(posMs: Long, durMs: Long): String {
    val pos = posMs / 1000
    val dur = durMs / 1000
    if (dur > 0) return "${pos / 60}:${(pos % 60).toString().padStart(2, '0')} / ${dur / 60}:${(dur % 60).toString().padStart(2, '0')}"
    return "${pos / 60}:${(pos % 60).toString().padStart(2, '0')}"
}
package com.radio.chinese.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
    viewModel: AudioLibraryViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
    showMiniPlayer: Boolean = true,
    searchQuery: String = ""
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredSources = uiState.sources.filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 本地音频权限请求
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, audioPermission)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            audioPermLauncher.launch(audioPermission)
        }
    }

    // 拦截系统返回键：只在音频库内部回退，不跳出 tab
    BackHandler(enabled = uiState.showBrowseContent || uiState.showFavorites) {
        if (uiState.showFavorites) {
            viewModel.hideFavorites()
        } else {
            viewModel.browseBack()
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
            TopAppBar(
                title = {
                    when {
                        uiState.showFavorites -> Text("收藏")
                        uiState.browsingSource != null -> Text(uiState.browsingSource!!.name)
                        else -> Text("我的音频库")
                    }
                },
                navigationIcon = {
                    if (uiState.browsingSource != null || uiState.showFavorites) {
                        IconButton(onClick = {
                            if (uiState.showFavorites) viewModel.hideFavorites()
                            else viewModel.browseBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (uiState.browsingSource != null) {
                        IconButton(onClick = { viewModel.refreshBrowse() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    } else if (!uiState.showFavorites) {
                        IconButton(onClick = { viewModel.showAddDialog() }) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
                    }
                }
            )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.showBrowseContent) {
                BrowseScreen(
                    items = uiState.browseItems,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onItemClick = { item ->
                        if (item.isFolder) viewModel.browseFolder(item.path)
                        else viewModel.playTrack(item)
                    },
                    onRefresh = { viewModel.refreshBrowse() },
                    onToggleFavorite = { item ->
                        viewModel.toggleFavorite(item.path, item.name, uiState.browsingSource?.name ?: "")
                    },
                    isFavorited = { path -> viewModel.isFavorited(path) }
                )
            } else if (uiState.showFavorites) {
                FavoritesList(
                    favorites = uiState.favorites,
                    onPlay = { fav -> viewModel.playFavorite(fav) },
                    onRemove = { path -> viewModel.toggleFavorite(path, "", "") }
                )
            } else if (filteredSources.isEmpty() && !uiState.isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("暂无音频库", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("点击右上角 + 添加音频来源\n支持本地、WebDAV、M3U 播放列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredSources) { source ->
                        SourceCard(source = source, onClick = { viewModel.browseSource(source) }, onDelete = { viewModel.deleteSource(source.id) })
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // MiniPlayer 播放条 — 只要有 currentTrack 就显示（外层已接管时隐藏）
            if (uiState.currentTrack != null && showMiniPlayer) {
                MiniPlayerBar(
                    track = uiState.currentTrack!!,
                    isPlaying = uiState.isPlaying,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    bitrateBps = uiState.bitrateBps,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onStop = { viewModel.stopPlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onNext = { viewModel.playNext() },
                    onPrevious = { viewModel.playPrevious() },
                    onCycleRepeat = { viewModel.cycleRepeatMode() },
                    repeatMode = uiState.repeatMode,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceCard(source: AudioSource, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = { showDeleteConfirm = true }
        ),
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
    bitrateBps: Int,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    repeatMode: com.radio.chinese.ui.library.RepeatMode,
    modifier: Modifier = Modifier
) {
    var sliderPos by remember(positionMs) { mutableFloatStateOf(positionMs.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()) {
            // 第1块：曲目名称（独占一行）
            MarqueeText(track.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)), enabled = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))

            // 第2块：进度条 + 时间/码率
            Slider(
                value = if (isDragging) sliderPos else positionMs.toFloat(),
                onValueChange = { sliderPos = it; isDragging = true },
                onValueChangeFinished = { onSeek(sliderPos.toLong()); isDragging = false },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPlaybackTime(positionMs, durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (bitrateBps > 0) Text("${bitrateBps / 1000}kbps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }

            // 第3块：功能按钮（居中）
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCycleRepeat) {
                    Text(
                        when (repeatMode) {
                            RepeatMode.ALL -> "全部"
                            RepeatMode.ONE -> "单曲"
                            RepeatMode.RANDOM -> "随机"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, "停止")
                }
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

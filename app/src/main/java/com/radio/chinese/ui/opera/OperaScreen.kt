package com.radio.chinese.ui.opera

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.DownloadedOpera
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.domain.model.OperaCategory
import com.radio.chinese.domain.model.OperaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperaScreen(
    onNavigateBack: () -> Unit,
    viewModel: OperaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("戏曲") },
                navigationIcon = {
                    if (!uiState.needAuth) {
                        val canBack = uiState.level != BrowseLevel.CATEGORIES || uiState.tabIndex == 1
                        if (canBack) {
                            IconButton(onClick = {
                                if (uiState.tabIndex == 1) viewModel.switchTab(0)
                                else viewModel.goBack()
                            }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.needAuth) {
                AuthCodeContent(
                    error = uiState.authError,
                    isConnecting = uiState.isConnecting,
                    onSubmit = viewModel::submitAuthCode
                )
            } else {
                TabRow(selectedTabIndex = uiState.tabIndex) {
                    Tab(selected = uiState.tabIndex == 0, onClick = { viewModel.switchTab(0) }, text = { Text("在线浏览") })
                    Tab(selected = uiState.tabIndex == 1, onClick = { viewModel.switchTab(1) }, text = { Text("已下载 (${uiState.localDownloads.size})") })
                }
                when (uiState.tabIndex) {
                    0 -> OnlineBrowseContent(uiState, viewModel)
                    1 -> DownloadedContent(uiState.localDownloads, uiState.currentFile?.fileId,
                        onPlay = { viewModel.playDownloadedFile(it) }, onDelete = { viewModel.deleteDownloaded(it.fileId) })
                }
            }

            if (uiState.currentFile != null) {
                MiniOperaPlayer(
                    file = uiState.currentFile!!,
                    isPlaying = uiState.isPlaying,
                    error = uiState.playError,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    bitrate = uiState.bitrate,
                    hasPrevious = uiState.currentIndex > 0,
                    hasNext = uiState.currentIndex >= 0 && uiState.currentIndex < uiState.currentPlaylist.size - 1,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onClose = { viewModel.stopPlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onSeekForward = { viewModel.seekForward() },
                    onSeekBackward = { viewModel.seekBackward() },
                    onRetry = { viewModel.retryPlayback() },
                    onPrevious = { viewModel.playPrevious() },
                    onNext = { viewModel.playNext() }
                )
            }
        }
    }
}

@Composable
private fun AuthCodeContent(error: String?, isConnecting: Boolean, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.fillMaxWidth().padding(32.dp), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Text("戏曲", style = MaterialTheme.typography.titleMedium)
                Text("请输入授权码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("授权码") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onSubmit(code) },
                    enabled = code.isNotBlank() && !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isConnecting) "连接中..." else "确认")
                }
            }
        }
    }
}

@Composable
private fun OnlineBrowseContent(uiState: OperaUiState, viewModel: OperaViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.searchQuery, onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("搜索戏曲文件") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (uiState.searchQuery.isNotEmpty()) IconButton(onClick = { viewModel.updateSearchQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "清除") } },
            singleLine = true, shape = RoundedCornerShape(28.dp)
        )

        if (uiState.selectedCategory != null && uiState.level != BrowseLevel.SEARCH_RESULTS) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(text = buildString { append(uiState.selectedCategory.name); if (uiState.selectedOpera != null) append(" / ${uiState.selectedOpera.name}") },
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }

        // 用 Box + weight(1f) 约束 LazyColumn 的高度，使其可以滚动
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                uiState.error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadCategories() }) { Text("重试") }
                    }
                }
                uiState.level == BrowseLevel.CATEGORIES -> {
                    if (uiState.categories.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无戏曲分类") }
                    else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.categories) { CategoryCard(it) { viewModel.selectCategory(it) } }
                    }
                }
                uiState.level == BrowseLevel.OPERAS -> {
                    if (uiState.operas.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("该分类下暂无剧目") }
                    else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(uiState.operas) { OperaCard(it) { viewModel.selectOpera(it) } }
                    }
                }
                uiState.level == BrowseLevel.FILES -> {
                    if (uiState.audioFiles.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("该剧目下暂无音频文件") }
                    else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(uiState.audioFiles) { file ->
                            AudioFileCard(file, uiState.downloadedIds.contains(file.fileId), uiState.downloadProgress[file.fileId],
                                onPlay = { viewModel.playFile(file) }, onDownload = { viewModel.downloadFile(file) },
                                isCurrentlyPlaying = uiState.currentFile?.fileId == file.fileId)
                        }
                    }
                }
                uiState.level == BrowseLevel.SEARCH_RESULTS -> {
                    if (uiState.searchResults.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (uiState.searchQuery.length < 2) "输入至少2个字符搜索" else "未找到匹配结果")
                    } else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(uiState.searchResults) { file ->
                            SearchResultCard(file, uiState.downloadedIds.contains(file.fileId), uiState.downloadProgress[file.fileId],
                                onPlay = { viewModel.playFile(file) }, onDownload = { viewModel.downloadFile(file) },
                                isCurrentlyPlaying = uiState.currentFile?.fileId == file.fileId)
                        }
                    }
                }
            }
        }
    }
}

// ========== 列表组件 ==========

@Composable private fun CategoryCard(cat: OperaCategory, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(cat.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable private fun OperaCard(opera: OperaItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(opera.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun AudioFileCard(file: OperaAudioFile, isDownloaded: Boolean, downloadProgress: Int?, onPlay: () -> Unit, onDownload: () -> Unit,
    isCurrentlyPlaying: Boolean = false) {
    val bgColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 播放指示器
            if (isCurrentlyPlaying) {
                Icon(Icons.Default.VolumeUp, contentDescription = "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (downloadProgress != null) CircularProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            else {
                IconButton(onClick = onPlay) { Icon(Icons.Default.PlayCircle, contentDescription = "播放", tint = MaterialTheme.colorScheme.primary) }
                if (isDownloaded) Icon(Icons.Default.CheckCircle, contentDescription = "已下载", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                else IconButton(onClick = onDownload) { Icon(Icons.Default.Download, contentDescription = "下载") }
            }
        }
    }
}

@Composable
private fun SearchResultCard(file: OperaAudioFile, isDownloaded: Boolean, downloadProgress: Int?, onPlay: () -> Unit, onDownload: () -> Unit,
    isCurrentlyPlaying: Boolean = false) {
    val bgColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isCurrentlyPlaying) {
                Icon(Icons.Default.VolumeUp, contentDescription = "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text("${file.categoryName} / ${file.operaName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (downloadProgress != null) CircularProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            else {
                IconButton(onClick = onPlay) { Icon(Icons.Default.PlayCircle, contentDescription = "播放", tint = MaterialTheme.colorScheme.primary) }
                if (!isDownloaded) IconButton(onClick = onDownload) { Icon(Icons.Default.Download, contentDescription = "下载") }
            }
        }
    }
}

@Composable
private fun DownloadedContent(downloads: List<DownloadedOpera>, currentFileId: Long?,
    onPlay: (DownloadedOpera) -> Unit, onDelete: (DownloadedOpera) -> Unit) {
    if (downloads.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp)); Text("暂无已下载戏曲", style = MaterialTheme.typography.bodyLarge)
        }
    } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(downloads, key = { it.fileId }) {
            val isPlaying = currentFileId == it.fileId
            val bgColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
            Card(modifier = Modifier.fillMaxWidth().clickable { onPlay(it) }, shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = bgColor)) {
                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(it.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("${it.categoryName} / ${it.operaName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onPlay(it) }) { Icon(Icons.Default.PlayCircle, contentDescription = "播放", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onDelete(it) }) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// ========== Mini Player ==========

@Composable
private fun MiniOperaPlayer(file: OperaAudioFile, isPlaying: Boolean, error: String?, positionMs: Long, durationMs: Long, bitrate: Int,
    hasPrevious: Boolean, hasNext: Boolean,
    onPlayPause: () -> Unit, onClose: () -> Unit, onSeek: (Long) -> Unit, onSeekForward: () -> Unit, onSeekBackward: () -> Unit,
    onRetry: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Surface(tonalElevation = 4.dp, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // 码率显示
                if (bitrate > 0) {
                    val bitrateText = if (bitrate >= 1000) "${bitrate / 1000} kbps" else "$bitrate bps"
                    Text(bitrateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp)) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (durationMs > 0) {
                Slider(value = positionMs.toFloat(), onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..durationMs.toFloat(), modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // 上一曲按钮
                IconButton(onClick = onPrevious, enabled = hasPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一曲", modifier = Modifier.size(32.dp), tint = if (hasPrevious) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)) }
                IconButton(onClick = onSeekBackward) { Icon(Icons.Default.Replay10, contentDescription = "后退15秒", modifier = Modifier.size(32.dp)) }
                FilledIconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = onSeekForward) { Icon(Icons.Default.Forward10, contentDescription = "快进15秒", modifier = Modifier.size(32.dp)) }
                // 下一曲按钮
                IconButton(onClick = onNext, enabled = hasNext) { Icon(Icons.Default.SkipNext, contentDescription = "下一曲", modifier = Modifier.size(32.dp), tint = if (hasNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)) }
            }
            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("重试", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

// ========== 工具 ==========

private fun formatTime(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

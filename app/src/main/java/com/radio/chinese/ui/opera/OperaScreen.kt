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
import java.util.concurrent.TimeUnit

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
                    if (uiState.level != com.radio.chinese.ui.opera.BrowseLevel.CATEGORIES || uiState.tabIndex == 1) {
                        IconButton(onClick = {
                            if (uiState.tabIndex == 1) viewModel.switchTab(0)
                            else viewModel.goBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (uiState.needPassword) {
                // 提取码输入界面
                PasswordInputContent(onSubmit = { viewModel.submitPassword(it) })
            } else {
                // Tab Row
                TabRow(selectedTabIndex = uiState.tabIndex) {
                    Tab(
                        selected = uiState.tabIndex == 0,
                        onClick = { viewModel.switchTab(0) },
                        text = { Text("在线浏览") }
                    )
                    Tab(
                        selected = uiState.tabIndex == 1,
                        onClick = { viewModel.switchTab(1) },
                        text = { Text("已下载 (${uiState.localDownloads.size})") }
                    )
                }

                when (uiState.tabIndex) {
                    0 -> OnlineBrowseContent(uiState = uiState, viewModel = viewModel)
                    1 -> DownloadedContent(
                        downloads = uiState.localDownloads,
                        onPlay = { viewModel.playFile(
                            OperaAudioFile(it.fileId, it.fileName, it.fileSize, it.categoryName, it.operaName),
                            it.localPath
                        ) },
                        onDelete = { viewModel.deleteDownloaded(it.fileId) }
                    )
                }
            }

            // Mini player bar
            if (uiState.currentFile != null) {
                MiniOperaPlayer(
                    file = uiState.currentFile!!,
                    isPlaying = uiState.isPlaying,
                    error = uiState.playError,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onClose = { viewModel.stopPlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onSeekForward = { viewModel.seekForward() },
                    onSeekBackward = { viewModel.seekBackward() },
                    onRetry = { viewModel.retryPlayback() }
                )
            }
        }
    }
}

@Composable
private fun PasswordInputContent(
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "河南戏曲音频",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "请输入分享提取码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("提取码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onSubmit(password) },
                    enabled = password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确认")
                }
            }
        }
    }
}

@Composable
private fun OnlineBrowseContent(
    uiState: OperaUiState,
    viewModel: OperaViewModel
) {
    Column {
        // Breadcrumb
        if (uiState.selectedCategory != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = buildString {
                        append(uiState.selectedCategory.name)
                        if (uiState.selectedOpera != null) append(" / ${uiState.selectedOpera.name}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadCategories() }) { Text("重试") }
                    }
                }
            }
            uiState.level == com.radio.chinese.ui.opera.BrowseLevel.CATEGORIES -> {
                if (uiState.categories.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无戏曲分类，请确认123云盘分享Key已配置",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.categories) { cat ->
                            CategoryCard(cat, onClick = { viewModel.selectCategory(cat) })
                        }
                    }
                }
            }
            uiState.level == com.radio.chinese.ui.opera.BrowseLevel.OPERAS -> {
                if (uiState.operas.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该分类下暂无剧目")
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(uiState.operas) { opera ->
                            OperaCard(opera, onClick = {
                                uiState.selectedCategory?.let { cat ->
                                    viewModel.selectOpera(cat, opera)
                                }
                            })
                        }
                    }
                }
            }
            uiState.level == com.radio.chinese.ui.opera.BrowseLevel.FILES -> {
                if (uiState.audioFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该剧目下暂无音频文件")
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(uiState.audioFiles) { file ->
                            AudioFileCard(
                                file = file,
                                isDownloaded = uiState.downloadedIds.contains(file.fileId),
                                downloadProgress = uiState.downloadProgress[file.fileId],
                                onPlay = { viewModel.playFile(file) },
                                onDownload = { viewModel.downloadFile(file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(category: OperaCategory, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                if (category.itemCount > 0) {
                    Text("${category.itemCount} 个剧目", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun OperaCard(opera: OperaItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(opera.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun AudioFileCard(
    file: OperaAudioFile,
    isDownloaded: Boolean,
    downloadProgress: Int?,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AudioFile, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (downloadProgress != null) {
                CircularProgressIndicator(progress = { downloadProgress / 100f },
                    modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayCircle, contentDescription = "播放",
                        tint = MaterialTheme.colorScheme.primary)
                }
                if (isDownloaded) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "已下载",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                } else {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = "下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedContent(
    downloads: List<DownloadedOpera>,
    onPlay: (DownloadedOpera) -> Unit,
    onDelete: (DownloadedOpera) -> Unit
) {
    if (downloads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Download, contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("暂无已下载戏曲", style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(downloads, key = { it.fileId }) { item ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.fileName, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.categoryName} / ${item.operaName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onPlay(item) }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "播放",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniOperaPlayer(
    file: OperaAudioFile,
    isPlaying: Boolean,
    error: String?,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 文件名 + 关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AudioFile, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(file.name, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭",
                        modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 进度条
            if (durationMs > 0) {
                Slider(
                    value = positionMs.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                // 时间显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSeekBackward) {
                    Icon(Icons.Default.Replay10, contentDescription = "后退15秒",
                        modifier = Modifier.size(32.dp))
                }
                FilledIconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = onSeekForward) {
                    Icon(Icons.Default.Forward10, contentDescription = "快进15秒",
                        modifier = Modifier.size(32.dp))
                }
            }

            // 错误信息 + 重试
            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(error, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

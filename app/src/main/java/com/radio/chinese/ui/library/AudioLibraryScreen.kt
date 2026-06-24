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
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType

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
                    onRefresh = { viewModel.refreshBrowse() }
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
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.sources) { source ->
                        SourceCard(
                            source = source,
                            onClick = { viewModel.browseSource(source) },
                            onDelete = { viewModel.deleteSource(source.id) }
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
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
                Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

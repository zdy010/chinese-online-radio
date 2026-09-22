package com.radio.chinese.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.ErrorState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.theme.Dimens

/**
 * 音频库内容区。
 *
 * 播放条不再在这里画：宿主 AudioMainScreen 用 Scaffold.bottomBar 统一承载，
 * 否则它会以 overlay 压在列表上，列表最后一项永远点不到。
 * 顶栏也交给宿主：此前 4 个页面各写一段 if (showTopBar) TopAppBar，
 * 而调用方一律传 false，于是整段是永不渲染的死代码，刷新按钮因此拿不到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLibraryScreen(
    viewModel: AudioLibraryViewModel = hiltViewModel(),
    searchQuery: String = ""
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredSources = uiState.sources.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }
    val filteredBrowseItems = uiState.browseItems.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }

    BackHandler(enabled = uiState.showBrowseContent) {
        viewModel.browseBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.showBrowseContent -> {
                BrowseScreen(
                    items = filteredBrowseItems,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onItemClick = { item ->
                        if (item.isFolder) viewModel.browseFolder(item.path)
                        else viewModel.playTrack(item)
                    },
                    onRefresh = { viewModel.refreshBrowse() },
                    onToggleFavorite = { item ->
                        val source = uiState.browsingSource
                        if (source != null) {
                            viewModel.toggleFavorite(source.id, item.path, item.name, source.name)
                        }
                    },
                    isFavorited = { path -> viewModel.isFavorited(path) }
                )
            }

            uiState.isLoading && uiState.sources.isEmpty() -> {
                LoadingState(text = "正在读取音频库…")
            }

            uiState.error != null && !uiState.isLoading -> {
                ErrorState(
                    message = uiState.error!!,
                    hint = "如果刚改过网络或网盘授权，重新加载试试",
                    onRetry = { viewModel.loadSources() }
                )
            }

            filteredSources.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.LibraryMusic,
                    title = if (searchQuery.isBlank()) "还没有音频库" else "没有匹配「$searchQuery」的音频库",
                    hint = if (searchQuery.isBlank()) {
                        "支持本地目录、WebDAV、M3U 播放列表"
                    } else {
                        "换个关键字试试"
                    },
                    actionLabel = if (searchQuery.isBlank()) "添加来源" else null,
                    onAction = if (searchQuery.isBlank()) { { viewModel.showAddDialog() } } else null
                )
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(Dimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
                ) {
                    items(filteredSources, key = { it.id }) { source ->
                        SourceCard(
                            source = source,
                            onClick = { viewModel.browseSource(source) },
                            onDelete = { viewModel.deleteSource(source.id) }
                        )
                    }
                }
            }
        }
    }

    // 添加来源对话框由宿主 AudioMainScreen 渲染：此前它只在本页（网络 Tab）合成，
    // 而右上角的“+”在四个 Tab 上都可见，在本地/收藏/最近里按它只会把 showAddDialog 置 true
    // 但什么也不会发生，而且这个标志会一直挂着，等用户切到网络 Tab 时突然弹框。
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceCard(source: AudioSource, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteConfirm = true }
            ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(Dimens.CardPadding)
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight),
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
                modifier = Modifier.size(Dimens.IconLarge)
            )
            Spacer(modifier = Modifier.width(Dimens.GapLarge))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = source.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 删除原先只挂在长按上，界面上没有任何提示，中老年用户发现不了
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "${source.name}的更多操作"
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("打开") },
                        onClick = {
                            showMenu = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除音频库") },
            text = { Text("确定要删除「${source.name}」吗？已缓存的内容会一并清理。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

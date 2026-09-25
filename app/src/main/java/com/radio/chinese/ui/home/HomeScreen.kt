package com.radio.chinese.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.ErrorState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.RadioMiniPlayerBar
import com.radio.chinese.ui.common.StationCover
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToCategory: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentStation by viewModel.playerManager.currentStation.collectAsState()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsState()
    val playbackState by viewModel.playerManager.playbackState.collectAsState()

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
            TopAppBar(windowInsets = NestedWindowInsets, title = { Text("时光收音机") }, actions = { IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") } } )
            }
        },
        bottomBar = {
            if (currentStation != null) {
                val statusText = when {
                    playbackState == Player.STATE_BUFFERING -> "缓冲中…"
                    isPlaying -> "正在播放"
                    else -> "已暂停"
                }
                RadioMiniPlayerBar(
                    title = currentStation!!.name,
                    subtitle = statusText,
                    coverUrl = currentStation!!.logoUrl,
                    isPlaying = isPlaying,
                    onPlayPause = { viewModel.playerManager.togglePlayPause() },
                    onClick = { onNavigateToPlayer(currentStation!!.id) }
                )
            }
        }
    ) { padding ->
        Column(modifier = if (showTopBar) Modifier.padding(padding) else Modifier) {
            // Category Chips (无空行)
            CategoryChipsRow(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = viewModel::selectCategory,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Station List
            when {
                uiState.isLoading -> {
                    LoadingState(text = "正在加载电台…")
                }
                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        hint = "检查网络后重试，也可以先听已缓存的节目",
                        onRetry = viewModel::retry
                    )
                }
                uiState.filteredStations.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "没有找到相关电台",
                        hint = "换个关键字，或点上方分类标签看看其它电台"
                    )
                }
                else -> {
                    val listPadding = PaddingValues(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.GapTiny,
                        bottom = if (currentStation != null) Dimens.MiniPlayerHeight else Dimens.GapTiny
                    )
                    val gap = Arrangement.spacedBy(Dimens.ListGap)
                    val cell: @Composable (RadioStation) -> Unit = { station ->
                        StationListItem(
                            station = station,
                            isPlaying = currentStation?.id == station.id && isPlaying,
                            isFavorite = uiState.favoriteIds.contains(station.id),
                            onClick = {
                                viewModel.playStation(station)
                                onNavigateToPlayer(station.id)
                            },
                            onFavoriteClick = { viewModel.toggleFavorite(station.id) }
                        )
                    }
                    if (isCarSurface()) {
                        // 横屏单列会把一半宽度浪费掉；Adaptive 让列数跟着分辨率与 density 自适配
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 300.dp),
                            contentPadding = listPadding,
                            horizontalArrangement = gap,
                            verticalArrangement = gap
                        ) {
                            gridItems(uiState.filteredStations, key = { it.id }) { station -> cell(station) }
                        }
                    } else {
                        LazyColumn(contentPadding = listPadding, verticalArrangement = gap) {
                            items(uiState.filteredStations, key = { it.id }) { station -> cell(station) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChipsRow(
    categories: List<Pair<String, String>>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // 车机档把筛选条凑到 88dp 短边；手机档不加约束，避免连 46.9dp 这种现存微差异都被动到
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap)
    ) {
        item {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { onCategorySelected(null) },
                label = { Text("全部") },
                modifier = Modifier.carTouchTarget()
            )
        }
        items(categories, key = { "chip-${it.first}" }) { (id, name) ->
            FilterChip(
                selected = selectedCategory == id,
                onClick = {
                    onCategorySelected(if (selectedCategory == id) null else id)
                },
                label = { Text(name) },
                modifier = Modifier.carTouchTarget()
            )
        }
    }
}

@Composable
fun StationListItem(
    station: RadioStation,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(Dimens.CardPadding)
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Station Logo：缺 logo 时不再是一片空白，而是有底色的收音机图标
            StationCover(
                url = station.logoUrl,
                size = Dimens.CoverSmall,
                label = station.name,
                contentDescription = station.name
            )

            Spacer(modifier = Modifier.width(Dimens.GapMedium))

            // Station Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isPlaying) {
                        Spacer(modifier = Modifier.width(Dimens.GapSmall))
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "正在播放",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimens.GapLarge)
                        )
                    }
                }
                if (station.frequency.isNotEmpty()) {
                    Text(
                        text = station.frequency,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Favorite Button
            IconButton(
                onClick = onFavoriteClick,
                modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

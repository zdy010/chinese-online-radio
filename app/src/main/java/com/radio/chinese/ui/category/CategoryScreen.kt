package com.radio.chinese.ui.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.ui.common.categoryIcon
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onNavigateToPlayer: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
    searchQuery: String = ""
) {
    val uiState by viewModel.uiState.collectAsState()
    // 用 rememberSaveable：页内状态在左右翻页时会重新创建，普通 remember 会把已选分类重置回网格首页
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                title = { Text(selectedCategory?.let { StationCategory.labelOf(it) } ?: "分类浏览") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedCategory != null) {
                            selectedCategory = null
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            }
        }
    ) { padding ->
        val m = if (showTopBar) Modifier.padding(padding) else Modifier
        if (selectedCategory == null) {
            // 此前这个页面完全不读 isLoading/error，加载中是一整片纯白，连「暂无分类」都没有
            when {
                uiState.isLoading && uiState.categories.isEmpty() -> {
                    LoadingState(text = "正在加载分类…", modifier = m)
                }

                uiState.categories.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Category,
                        title = "还没有可浏览的分类",
                        hint = "先在设置里添加电台源，再回来看看",
                        modifier = m
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = if (isCarSurface()) GridCells.Adaptive(minSize = 240.dp)
                        else GridCells.Fixed(2),
                        modifier = m,
                        contentPadding = PaddingValues(Dimens.ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
                        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium)
                    ) {
                        items(uiState.categories, key = { it.first }) { (id, name) ->
                            CategoryCard(
                                categoryId = id,
                                categoryName = name,
                                stationCount = uiState.stations.count { it.category == id },
                                onClick = { selectedCategory = id }
                            )
                        }
                    }
                }
            }
        } else {
            // Stations in selected category
            val stations = uiState.stations.filter { it.category == selectedCategory && (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)) }
            val currentStation by viewModel.playerManager.currentStation.collectAsState()
            val isPlaying by viewModel.playerManager.isPlaying.collectAsState()

            Column(modifier = m) {
                if (stations.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Radio,
                        title = "这个分类下还没有电台",
                        hint = "换个分类看看，或搜索其它电台"
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Dimens.ScreenPadding),
                        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
                    ) {
                        items(stations, key = { it.id }) { station ->
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
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    categoryId: String,
    categoryName: String,
    stationCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large   // 16dp，与 AppShapes.large 同值，仅去裸字面量
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = categoryIcon(categoryId),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = categoryName,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$stationCount 个电台",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
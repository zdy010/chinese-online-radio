package com.radio.chinese.ui.category

import androidx.activity.compose.BackHandler
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
import com.radio.chinese.ui.common.carTouchTarget
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
    onNavigateToTag: (String?) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
    searchQuery: String = ""
) {
    val uiState by viewModel.uiState.collectAsState()
    // 用 rememberSaveable：页内状态在左右翻页时会重新创建，普通 remember 会把已选分类重置回网格首页
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // 页签宿主里 showTopBar=false，TopAppBar 的返回钮不存在；系统返回又会被 RadioScreen 的
    // “双击退出”兜底吃掉——分类列表进得去回不来。这里把返回键接回分类网格。
    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }

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
                    // 入口放在网格上方而不是网格首格：占用 items 的 key 会与分类卡碰撞，
                    // 也会打乱分类已有的网格排布
                    Column(m) {
                        TagBrowseEntry(
                            tagCount = uiState.tagsWithCount.size,
                            onClick = { onNavigateToTag(null) },
                        )
                        LazyVerticalGrid(
                            columns = if (isCarSurface()) GridCells.Adaptive(minSize = 240.dp)
                            else GridCells.Fixed(2),
                            modifier = Modifier.weight(1f),
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
            }
        } else {
            // Stations in selected category
            // 筛选规则不在这页里重写一遍：走 StationFilter，搜索范畴与首页保持一致
            // （以前这里只比台名，于是分类页搜「豫剧」「英国」都搜不到）
            val stations = com.radio.chinese.domain.model.StationFilter
                .apply(uiState.stations, selectedCategory, searchQuery)
            val currentStation by viewModel.playerManager.currentStation.collectAsState()
            val isPlaying by viewModel.playerManager.isPlaying.collectAsState()

            Column(modifier = m) {
                if (!showTopBar) {
                    // 无顶栏的页签形态给一条可见的返回行：不能只靠“滑回上一页/返回键”这种看不见的出路
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .carTouchTarget()
                            .clickable { selectedCategory = null },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回分类")
                        Spacer(Modifier.width(Dimens.GapSmall))
                        Text(StationCategory.labelOf(selectedCategory), style = MaterialTheme.typography.titleMedium)
                    }
                }
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

/**
 * 按标签浏览的入口行。
 *
 * 做成网格上方的一整行而不是网格首格：当 tagCount 为 0 时网格只有分类卡，
 * 把入口当成网格第一项会让列数与 key 的对应关系变得绕。0 个标签时写清楚原因，
 * 不拿一个点进去只会看到空列表的入口骗人点。
 */
@Composable
private fun TagBrowseEntry(tagCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall)
            .clickable(enabled = tagCount > 0, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(Dimens.IconLarge),
            )
            Spacer(modifier = Modifier.width(Dimens.GapMedium))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "按标签浏览",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (tagCount > 0) "$tagCount 个标签可选" else "暂无可用标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
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
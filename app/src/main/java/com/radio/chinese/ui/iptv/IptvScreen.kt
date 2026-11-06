package com.radio.chinese.ui.iptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.IptvCategory
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NowPlayingState
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.rememberNowPlaying
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

/**
 * iptv 一级 tab（spec V5）：搜索优先 + 四入口，**不平铺 12k 条**。
 *
 * 规则一句话：空查询看入口，有查询看结果。分类与国家是两级入口，
 * 进去才按标签/中文国名列频道；3,557 条没有 tags 的记录只能靠搜索/国家/最近/收藏到达，
 * 这是数据形状决定的，不为了补洞硬造一个巨大的「其他」格子。
 */
@Composable
fun IptvScreen(
    playerManager: PlayerManager,
    onNavigateToPlayer: (String) -> Unit,
    viewModel: IptvViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val onRowClick = rememberStationRowOnClick(playerManager, onNavigateToPlayer)
    val nowPlaying = rememberNowPlaying(playerManager)
    // "" = 首屏四入口；categories/countries/recent/favorite = 二级入口页；
    // "cat:<label>" / "cty:<name>" = 具体频道列表。用 String 是为了 rememberSaveable 直接可存。
    var mode by rememberSaveable { mutableStateOf("") }

    // 切台基准（V11）：注册当前可见集合。清理集中在 MainScreen 按路由做 ——
    // 从浏览页跳播放页时浏览页也会 dispose，在 onDispose 里 clear 会把刚注册的基准清掉。
    LaunchedEffect(ui.visible) { playerManager.setSkipBase(ui.visible) }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBarRow(
            value = ui.query,
            onValueChange = { q ->
                viewModel.updateQuery(q)
                if (q.isNotEmpty()) mode = ""
            },
            placeholder = "搜索频道名称、别名或国家",
        )

        if (ui.isLoading) {
            LoadingState("正在读取内置频道清单…", modifier = Modifier.weight(1f))
            return@Column
        }
        if (ui.loadFailed) {
            EmptyState(
                icon = Icons.Default.Tv,
                title = "频道清单不可用",
                hint = "内置清单缺失或解析失败，请重新安装应用",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        val searching = ui.query.isNotBlank()
        if (!searching && mode.isNotEmpty()) {
            BrowseTopBar(title = modeTitle(mode), onBack = { mode = parentOf(mode) })
            if (mode == "categories") {
                Text(
                    text = "按上游标签聚合；没有分类标签的频道请用搜索或国家入口查找",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
                )
            }
        }

        val favoriteIds = ui.favorites.map { it.id }.toSet()

        when {
            searching -> {
                if (ui.visible.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Tv,
                        title = "没有匹配的频道",
                        hint = "试试频道别名、国家名或路径名",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    StationGrid(
                        stations = ui.visible,
                        nowPlaying = nowPlaying,
                        favoriteIds = favoriteIds,
                        onRowClick = onRowClick,
                        onFavoriteClick = viewModel::toggleFavorite,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            mode.isEmpty() -> EntryGrid(
                total = ui.visible.size,
                categoryCount = ui.categories.size,
                countryCount = ui.countries.size,
                recentCount = ui.recents.size,
                favoriteCount = ui.favorites.size,
                onOpen = { mode = it },
                modifier = Modifier.weight(1f),
            )

            mode == "categories" -> LabelGrid(
                entries = ui.categories,
                icon = Icons.Default.Category,
                onOpen = { mode = "cat:$it" },
                modifier = Modifier.weight(1f),
            )

            mode == "countries" -> LabelGrid(
                entries = ui.countries,
                icon = Icons.Default.Public,
                onOpen = { mode = "cty:$it" },
                modifier = Modifier.weight(1f),
            )

            else -> {
                val list = listFor(ui, mode)
                if (list.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Tv,
                        title = "这里还没有频道",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    StationGrid(
                        stations = list,
                        nowPlaying = nowPlaying,
                        favoriteIds = favoriteIds,
                        onRowClick = onRowClick,
                        onFavoriteClick = viewModel::toggleFavorite,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun modeTitle(mode: String): String = when {
    mode == "categories" -> "全部分类"
    mode == "countries" -> "全部国家/地区"
    mode == "recent" -> "最近播放"
    mode == "favorite" -> "我的收藏"
    mode.startsWith("cat:") -> mode.removePrefix("cat:")
    mode.startsWith("cty:") -> mode.removePrefix("cty:")
    else -> "频道列表"
}

private fun parentOf(mode: String): String = when {
    mode.startsWith("cat:") -> "categories"
    mode.startsWith("cty:") -> "countries"
    else -> ""
}

private fun listFor(ui: IptvUiState, mode: String): List<RadioStation> = when {
    mode == "recent" -> ui.recents
    mode == "favorite" -> ui.favorites
    mode.startsWith("cat:") -> {
        val label = mode.removePrefix("cat:")
        // 与 IptvViewLogic 的计数同一套翻译：先翻中文标签再比，别用原始 key
        ui.visible.filter { st -> st.tags.any { IptvCategory.labelOf(it) == label } }
    }
    mode.startsWith("cty:") -> ui.visible.filter { it.country == mode.removePrefix("cty:") }
    else -> emptyList()
}

@Composable
private fun BrowseTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSecondary)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(Dimens.GapSmall))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EntryGrid(
    total: Int,
    categoryCount: Int,
    countryCount: Int,
    recentCount: Int,
    favoriteCount: Int,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = if (isCarSurface()) GridCells.Adaptive(300.dp) else GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
    ) {
        item {
            EntryCard(Icons.Default.Category, "分类浏览", "$categoryCount 类 · 共 $total 个频道") {
                onOpen("categories")
            }
        }
        item {
            EntryCard(Icons.Default.Public, "国家/地区", "$countryCount 个国家 · $total 个频道") {
                onOpen("countries")
            }
        }
        item {
            EntryCard(Icons.Default.History, "最近播放", "$recentCount 个频道") { onOpen("recent") }
        }
        item {
            EntryCard(Icons.Default.Favorite, "我的收藏", "$favoriteCount 个频道") { onOpen("favorite") }
        }
    }
}

@Composable
private fun LabelGrid(
    entries: List<Pair<String, Int>>,
    icon: ImageVector,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = if (isCarSurface()) GridCells.Adaptive(300.dp) else GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
    ) {
        // label 已按中文聚合过，全表唯一：上游新增分类都落到「其他」也只会有一个格子
        items(entries, key = { it.first }) { (label, count) ->
            EntryCard(icon, label, "$count 个频道") { onOpen(label) }
        }
    }
}

@Composable
private fun StationGrid(
    stations: List<RadioStation>,
    nowPlaying: NowPlayingState,
    favoriteIds: Set<String>,
    onRowClick: (RadioStation) -> Unit,
    onFavoriteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = if (isCarSurface()) GridCells.Adaptive(300.dp) else GridCells.Fixed(1),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
    ) {
        items(stations, key = { it.id }) { station ->
            StationListItem(
                station = station,
                isPlaying = nowPlaying.matches(station),
                isFavorite = station.id in favoriteIds,
                onClick = { onRowClick(station) },
                onFavoriteClick = { onFavoriteClick(station.id) },
                // iptv 不接 EPG：不挂"正在播出"假标记
            )
        }
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchMin)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardPadding).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconLarge),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Dimens.GapMedium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

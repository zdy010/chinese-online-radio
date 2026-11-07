package com.radio.chinese.ui.iptv

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Place
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
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.domain.model.IptvCategory
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NowPlayingState
import com.radio.chinese.ui.common.RadioMiniPlayerBar
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.rememberNowPlaying
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
    // 输入框绑同步 query，不绑 uiState（后者在 Default 线程重算，会回滚输入）
    val queryText by viewModel.query.collectAsState()
    // 迷你条要的是"这台还在不在播"，与列表的 isPlaying 标记分开取，避免搜索过滤掉当前台时看不到
    val current by playerManager.currentStation.collectAsState()
    val playing by playerManager.isPlaying.collectAsState()
    val playbackError by playerManager.error.collectAsState()
    // iptv 是视频：点台就该立刻看到画面，不能沿用电台"先留在列表、再点才进播放页"的语义。
    // 当前台正在播时只导航，不重复起播（重复 playStation 会重置轮转，打断正在播的流）。
    val onRowClick: (RadioStation) -> Unit = { station ->
        if (playerManager.currentStation.value?.id != station.id) {
            playerManager.playStation(station)
        }
        onNavigateToPlayer(station.id)
    }
    val nowPlaying = rememberNowPlaying(playerManager)
    // "" = 首屏四入口；categories/countries/recent/favorite = 二级入口页；
    // "cat:<label>" / "cty:<name>" = 具体频道列表。用 String 是为了 rememberSaveable 直接可存。
    var mode by rememberSaveable { mutableStateOf("") }

    // 系统返回键先退"搜索态"、再退"二级列表"，不要一步退出 iptv tab。
    // 不拦这一步时，在分类/国家列表按返回会直接回到电台页，看起来像"点进去就回不来了"。
    BackHandler(enabled = ui.query.isNotEmpty()) { viewModel.updateQuery("") }
    BackHandler(enabled = ui.query.isBlank() && mode.isNotEmpty()) { mode = parentOf(mode) }

    // 切台基准（V11）：只在"有具体列表"时注册（搜索/分类/国家/收藏/最近）。
    // 首屏四入口也把整表注册进去的话，电台正在播时进 iptv tab 按一下媒体键，
    // nextStation 会因"当前台不在表里"取首项 —— 用户被切到一个随机 iptv 频道。
    // 清理集中在 MainScreen 按路由做（浏览页跳播放页时也会 dispose）。
    LaunchedEffect(ui.visible, mode, ui.query) {
        val hasList = ui.query.isNotBlank() || mode.isNotEmpty()
        playerManager.setSkipBase(if (hasList) ui.visible else null)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBarRow(
            value = queryText,
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
            BrowseTopBar(title = modeTitle(mode, ui), onBack = { mode = parentOf(mode) })
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
                regionCount = ui.regions.size,
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

            mode == "regions" -> LabelGrid(
                entries = ui.regions,
                icon = Icons.Default.Place,
                onOpen = { mode = "rgn:$it" },
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

        // 迷你条：从播放页返回后还能看到"正在播什么"，点它回专用播放页。
        // 只有 iptv 台才显示：电台台有自己的迷你条，不该在 iptv 页出现。
        val iptvNow = current?.takeIf { it.id.startsWith(IptvStationRepository.PREFIX) }
        if (iptvNow != null) {
            RadioMiniPlayerBar(
                title = com.radio.chinese.domain.model.StationDisplayName.of(iptvNow),
                subtitle = playbackError ?: if (playing) "正在播放" else "已暂停",
                coverUrl = iptvNow.logoUrl,
                isPlaying = playing,
                onPlayPause = { playerManager.togglePlayPause() },
                onClick = { onNavigateToPlayer(iptvNow.id) },
            )
        }
    }
}

/**
 * 二级列表标题。带上条目数：既让用户知道这一页有多大，也让验收能直接读到
 * "点进新闻后有 1003 个频道"，不必靠数列表项。
 */
private fun modeTitle(mode: String, ui: IptvUiState): String = when {
    mode == "categories" -> "全部分类 · ${ui.categories.size} 类"
    mode == "countries" -> "全部国家/地区 · ${ui.countries.size} 个"
    mode == "regions" -> "省份/区域 · ${ui.regions.size} 个"
    mode.startsWith("rgn:") -> {
        val name = mode.removePrefix("rgn:")
        "$name · ${ui.visible.count { it.region == name }} 个频道"
    }
    mode == "recent" -> "最近播放 · ${ui.recents.size} 个频道"
    mode == "favorite" -> "我的收藏 · ${ui.favorites.size} 个频道"
    mode.startsWith("cat:") -> {
        val label = mode.removePrefix("cat:")
        val n = ui.visible.count { st -> st.tags.any { IptvCategory.labelOf(it) == label } }
        "$label · $n 个频道"
    }
    mode.startsWith("cty:") -> {
        val name = mode.removePrefix("cty:")
        "$name · ${ui.visible.count { it.country == name }} 个频道"
    }
    else -> "频道列表"
}

private fun parentOf(mode: String): String = when {
    mode.startsWith("cat:") -> "categories"
    mode.startsWith("cty:") -> "countries"
    mode.startsWith("rgn:") -> "regions"
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
    mode.startsWith("rgn:") -> ui.visible.filter { it.region == mode.removePrefix("rgn:") }
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
    regionCount: Int,
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
            EntryCard(Icons.Default.Public, "国家/地区", "$countryCount 个国家/地区") {
                onOpen("countries")
            }
        }
        item {
            EntryCard(Icons.Default.Place, "省份/区域", "$regionCount 个省/区域") {
                onOpen("regions")
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

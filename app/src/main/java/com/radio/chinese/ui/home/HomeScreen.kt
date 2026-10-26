package com.radio.chinese.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.model.EpgProgram
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.ErrorState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.MarqueeText
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.NowPlayingWave
import com.radio.chinese.ui.common.RadioMiniPlayerBar
import com.radio.chinese.ui.common.SectionHeader
import com.radio.chinese.ui.common.StatusChip
import com.radio.chinese.ui.common.StationActionDialog
import com.radio.chinese.ui.common.StationCover
import com.radio.chinese.ui.common.StationSubtitleText
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
    val epgNowTitles by viewModel.epgNowTitles.collectAsState()
    val epgStationIds = viewModel.epgStationIds
    val currentStation by viewModel.playerManager.currentStation.collectAsState()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsState()
    val playbackState by viewModel.playerManager.playbackState.collectAsState()
    // 长按电台弹出的管理菜单（与节目源管理页同一套交互），方便随时编辑
    var actionStation by remember { mutableStateOf<RadioStation?>(null) }

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
            // 电视伴音直达卡已按用户要求移除：它把最贵的首屏顶部位置占住了，
            // 而下方分类 chips 的首项就是电视伴音（orderedForBrowse 置顶），入口并没有丢。

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
                uiState.filteredStations.isEmpty() && uiState.programResults.isEmpty() -> {
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
                        bottom = if (currentStation != null) Dimens.MiniPlayerSlim else Dimens.GapTiny
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
                            onFavoriteClick = { viewModel.toggleFavorite(station.id) },
                            onLongClick = { actionStation = station },
                            nowProgram = epgNowTitles[station.id],
                            hasEpg = station.id in epgStationIds,
                        )
                    }

                    // 节目搜索只在「全部」页分出一组：地区/分类/收藏页共用同一个输入框，
                    // 但只做电台过滤，否则同一次输入在两个页签里会给出两套语义
                    val programs = uiState.programResults
                    val stationById = if (programs.isEmpty()) emptyMap()
                    else uiState.stations.associateBy { it.id }
                    val onProgramClick: (EpgProgram) -> Unit = { program ->
                        stationById[program.stationId]?.let { station ->
                            viewModel.playStation(station)
                            onNavigateToPlayer(station.id)
                        }
                    }
                    val hasStationResults = uiState.filteredStations.isNotEmpty()

                    if (isCarSurface()) {
                        // 横屏单列会把一半宽度浪费掉；Adaptive 让列数跟着分辨率与 density 自适配
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 300.dp),
                            contentPadding = listPadding,
                            horizontalArrangement = gap,
                            verticalArrangement = gap
                        ) {
                            if (programs.isNotEmpty()) {
                                item(key = "program-header", span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader(title = "节目")
                                }
                                gridItems(
                                    items = programs,
                                    key = { "program-${it.stationId}-${it.startMin}" },
                                    span = { GridItemSpan(maxLineSpan) }
                                ) { program ->
                                    ProgramResultCard(
                                        program = program,
                                        stationName = stationById[program.stationId]?.name,
                                        onClick = { onProgramClick(program) }
                                    )
                                }
                                if (hasStationResults) {
                                    item(key = "station-header", span = { GridItemSpan(maxLineSpan) }) {
                                        SectionHeader(title = "电台")
                                    }
                                }
                            }
                            gridItems(uiState.filteredStations, key = { it.id }) { station -> cell(station) }
                        }
                    } else {
                        LazyColumn(contentPadding = listPadding, verticalArrangement = gap) {
                            if (programs.isNotEmpty()) {
                                item(key = "program-header") { SectionHeader(title = "节目") }
                                items(
                                    items = programs,
                                    key = { "program-${it.stationId}-${it.startMin}" }
                                ) { program ->
                                    ProgramResultCard(
                                        program = program,
                                        stationName = stationById[program.stationId]?.name,
                                        onClick = { onProgramClick(program) }
                                    )
                                }
                                if (hasStationResults) {
                                    item(key = "station-header") { SectionHeader(title = "电台") }
                                }
                            }
                            items(uiState.filteredStations, key = { it.id }) { station -> cell(station) }
                        }
                    }
                }
            }
        }
    }

    actionStation?.let { station ->
        StationActionDialog(
            station = station,
            // 主页只列有效电台，这里的“恢复为有效”分支不会命中，但仍传真实实现
            isInvalid = false,
            isCustom = uiState.customIds.contains(station.id),
            onDismiss = { actionStation = null },
            onMarkInvalid = {
                viewModel.markStationInvalid(station.id)
                actionStation = null
            },
            onMarkValid = {
                viewModel.restoreStation(station.id)
                actionStation = null
            },
            onDelete = {
                viewModel.deleteCustomStation(station.id)
                actionStation = null
            }
        )
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

/**
 * 节目搜索结果的一行：节目名 / 电台名 · 时段。点击起播该台并进播放页。
 *
 * 台名查不到时不渲染电台名而只留时段：节目来自缓存，对应的台理论上一定在库里，
 * 但真的碰上已下架的台时，也不该把空台名拼成一个孤零零的分隔符。
 */
@Composable
private fun ProgramResultCard(
    program: EpgProgram,
    stationName: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        stationName,
                        EpgQuery.formatRange(program.startMin, program.endMin)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = Dimens.GapSmall)
                    .size(Dimens.IconLarge)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationListItem(
    station: RadioStation,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    nowProgram: String? = null,
    hasEpg: Boolean = false,
) {
    // 不传 onLongClick 时保持原来的 clickable：只在需要长按弹菜单的页面才升级，
    // 避免给分类页等复用方添上不需要的长按响应
    val itemModifier = modifier
        .fillMaxWidth()
        .let {
            if (onLongClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            else it.clickable(onClick = onClick)
        }
    Card(
        modifier = itemModifier,
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
                        // 四根错相跳动的彩条：静态 GraphicEq 图标既不像「在响」也不够扎眼
                        NowPlayingWave(size = Dimens.IconMedium)
                    }
                    // 已接入节目单的台挂一个标记：与此刻有无在播节目无关，空档时也要在
                    if (hasEpg) {
                        Spacer(modifier = Modifier.width(Dimens.GapSmall))
                        StatusChip(text = "单")
                    }
                }
                // 副标题规则在 stationSubtitle 一处定义，与播放器、管理页共用
                StationSubtitleText(station = station)
                // 正在播出的节目名：太长时单行走马灯滚动，不折行顶高列表行
                if (!nowProgram.isNullOrBlank()) {
                    MarqueeText(
                        text = "正在播出：$nowProgram",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = Dimens.GapTiny)
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

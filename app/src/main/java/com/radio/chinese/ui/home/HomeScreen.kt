package com.radio.chinese.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.radio.chinese.domain.model.IptvBrief
import com.radio.chinese.domain.model.StationDisplayName
import com.radio.chinese.ui.common.LocalEpgInline
import com.radio.chinese.ui.common.focusRingVisual
import com.radio.chinese.ui.common.NowPlayingWave
import com.radio.chinese.ui.common.playabilityNameColor
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.common.SectionHeader
import com.radio.chinese.ui.common.StatusChip
import com.radio.chinese.ui.common.StationActionDialog
import com.radio.chinese.ui.common.StationCover
import com.radio.chinese.ui.common.carChipTarget
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.common.stationSubtitleOf
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.isTvSurface

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
    // EPG 状态改由 RadioPagerHost 通过 LocalEpgInline 提供，这里不再各自订阅
    val currentStation by viewModel.playerManager.currentStation.collectAsState()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsState()
    // 长按电台弹出的管理菜单（与节目源管理页同一套交互），方便随时编辑
    var actionStation by remember { mutableStateOf<RadioStation?>(null) }
    // 行点击统一语义：点非当前台只起播并留在列表，点当前台才进播放页
    val onRowClick = rememberStationRowOnClick(viewModel.playerManager, onNavigateToPlayer)

    // 迷你播放条上移到 RadioScreen（RadioPagerHost 外层）：五个页签共用一条。
    // 以前只有「全部」页挂了迷你条，翻到地区/分类/收藏/最近后底部什么都没有，
    // 在播的行又不够突出，用户感知就是「页面没有刷新、不知道播到哪了」。
    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
            TopAppBar(windowInsets = NestedWindowInsets, title = { Text("时光收音机") }, actions = { IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") } } )
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
                        // 迷你条已上移到 RadioScreen，列表底部不再被覆盖，固定留个小间距即可
                        bottom = Dimens.GapTiny
                    )
                    val gap = Arrangement.spacedBy(Dimens.ListGap)
                    val cell: @Composable (RadioStation) -> Unit = { station ->
                        StationListItem(
                            station = station,
                            isPlaying = currentStation?.id == station.id && isPlaying,
                            isFavorite = uiState.favoriteIds.contains(station.id),
                            onClick = { onRowClick(station) },
                            onFavoriteClick = { viewModel.toggleFavorite(station.id) },
                            onLongClick = { actionStation = station },
                        )
                    }

                    // 节目搜索只在「全部」页分出一组：地区/分类/收藏页共用同一个输入框，
                    // 但只做电台过滤，否则同一次输入在两个页签里会给出两套语义
                    val programs = uiState.programResults
                    val stationById = if (programs.isEmpty()) emptyMap()
                    else uiState.stations.associateBy { it.id }
                    val onProgramClick: (EpgProgram) -> Unit = { program ->
                        // 从节目名点进同一个台：也走行点击语义，不在当前台时只起播不跳转
                        stationById[program.stationId]?.let { onRowClick(it) }
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
    // 芯片是「宽而浅」的导航件：车机档走 carChipTarget（竖向 64dp）而不是
    // carTouchTarget（88dp），88dp 会把首屏整整吃掉一条；手机档不加约束，
    // 避免连 46.9dp 这种现存微差异都被动到
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
                modifier = Modifier.carChipTarget()
            )
        }
        items(categories, key = { "chip-${it.first}" }) { (id, name) ->
            FilterChip(
                selected = selectedCategory == id,
                onClick = {
                    onCategorySelected(if (selectedCategory == id) null else id)
                },
                label = { Text(name) },
                modifier = Modifier.carChipTarget()
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
                .padding(horizontal = Dimens.CardPadding, vertical = Dimens.GapSmall)
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
) {
    // EPG 由电台页签宿主通过 Local 统一提供：地区/分类/收藏/最近/标签页复用本组件时
    // 不用各自记得传参，也不会再出现「只有全部页有节目单」。
    val epg = LocalEpgInline.current
    val hasEpg = station.id in epg.stationIds
    val nowProgram = epg.nowTitles[station.id]
    val epgPrograms = epg.expanded[station.id]
    val epgMinute = epg.minute
    val onEpgToggle: (() -> Unit)? = if (hasEpg) { { epg.onToggle(station.id) } } else null
    // 焦点状态用 InteractionSource 观察，而不是 onFocusChanged：
    // LazyVerticalGrid 里焦点在行间移动时，失焦的那一行不一定收到 isFocused=false，
    // 旧描边会一直留在原处（电视上表现为「按键后列表一直闪/两个框」）。
    // 让 clickable 和焦点观察共用同一个 interactionSource，状态才可靠。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // 不传 onLongClick 时保持原来的 clickable：只在需要长按弹菜单的页面才升级，
    // 避免给分类页等复用方添上不需要的长按响应
    val itemModifier = modifier
        .fillMaxWidth()
        .let {
            if (onLongClick != null) it.combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ) else it.clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
        }
    // 电视 10ft 场景下 22% 染色 + 6dp 描边隔着几米看不见：聚焦行整块放大 1.05、
    // 染色加深到 32%、描边加粗到 10dp，并抬高 zIndex 压住相邻行；手机/车机维持原参数。
    val tv = isTvSurface()
    Card(
        // 电视/车机上方向键移焦点时必须有可见反馈：手机档点按后的短暂高亮也无害
        modifier = Modifier
            .focusRingVisual(
                focused = focused,
                scale = if (tv) 1.05f else 1f,
                zIndex = if (tv) 1f else 0f,
                tintAlpha = if (tv) 0.32f else 0.22f,
                borderWidth = if (tv) 10.dp else 6.dp,
            )
            .then(itemModifier),
        // 当前播放的行加主色描边：列表里只有一个小波浪标记不够扎眼，用户翻到别的
        // 页签再回来就「不知道播到哪了」。描边随 isPlaying 出现/消失，暂停后不残留。
        border = if (isPlaying) BorderStroke(
            width = if (isCarSurface() || tv) 2.dp else 1.dp,
            color = MaterialTheme.colorScheme.primary
        ) else null,
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
        Row(
            // 竖向内边距从 CardPadding(12) 收到 GapSmall(8)：行高里真正吃掉空间的
            // 不是文字而是「封面 + 上下内边距」，手机档从 72dp 降到 64dp，
            // 车机档从 98dp 降到 72dp。横向留白不动，不改变文字与封面的间距观感。
            modifier = Modifier
                .padding(horizontal = Dimens.CardPadding, vertical = Dimens.GapSmall)
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Station Logo：缺 logo 时不再是一片空白，而是有底色的收音机图标
            // 占位缩字跟随显示名：中文台名会让封面取前两字（「少儿频道」→「少儿」），
            // 用原名「Jiangxi Children's Channel」则会得到 JCC，和旁边的中文台名对不上
            val displayName = StationDisplayName.of(station)
            StationCover(
                url = station.logoUrl,
                size = Dimens.CoverSmall,
                label = displayName,
                contentDescription = displayName
            )

            Spacer(modifier = Modifier.width(Dimens.GapMedium))

            // Station Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 频率并到台名后面同一行（用户实测：频率单独占一行太浪费）。
                    // 用同一段富文本的两种样式，而不是 Row 里摆两个 Text：
                    // 两个 Text 会抢宽度，名字一长就把后面的波浪标记与「单」字挤出去；
                    // 单段富文本是整行一起省略，控件永远留在原位。
                    // iptv 的简介单独一行（下面走马灯），不挤在台名后面
                    val sub = if (station.id.startsWith("iptv:")) "" else stationSubtitleOf(station)
                    val nameStyle = MaterialTheme.typography.titleSmall
                    // 台名按可用性着色：未知分数返回 null，保持默认色。
                    // 必须在 buildAnnotatedString 外取：那个 lambda 不是 @Composable
                    val stationNameColor = playabilityNameColor(station.id)
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                nameStyle.copy(color = stationNameColor ?: Color.Unspecified).toSpanStyle()
                            ) { append(StationDisplayName.of(station)) }
                            if (sub.isNotBlank()) {
                                append("  ")
                                withStyle(
                                    MaterialTheme.typography.bodySmall
                                        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        .toSpanStyle()
                                ) { append(sub) }
                            }
                        },
                        style = nameStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
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
                    // 只有代理/VPN 能连通的 iptv 频道：实测标记，台名后直接给角标，
                    // 不用等点开播不出来才发现。角标只认字段，不依赖当前是否连着 VPN。
                    if (station.vpnOnly) {
                        Spacer(modifier = Modifier.width(Dimens.GapSmall))
                        StatusChip(
                            text = "需 VPN",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                // iptv 简介单独一行。不用 MarqueeText：它的 SubcomposeLayout 在
                // LazyGrid 里每次重组都要重新 subcompose 测量，电视上按方向键移动焦点时
                // 会造成测量震荡（列表列宽在相邻帧里跳变、持续滚动停不下来）。
                // 简介是「国家 · 分类 · 语言」这种短文本，单行省略就够，本来就滚不起来。
                if (station.id.startsWith("iptv:")) {
                    val brief = IptvBrief.of(station)
                    if (brief.isNotBlank()) {
                        Text(
                            text = brief,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Dimens.GapTiny),
                        )
                    }
                }
                // 正在播出的节目名：太长时单行走马灯滚动，不折行顶高列表行；
                // 没有节目时整块不渲染，这一行台就只有两行高
                // 展开节目单时不再显示这一行：下面面板已有完整的「正在播出 + 时间」，重复只顶高行
                if (!nowProgram.isNullOrBlank() && epgPrograms == null) {
                    MarqueeText(
                        text = "正在播出：$nowProgram",
                        // 滚动只给正在播放的行：以前所有带节目单的行都在滚，满屏乱动，
                        // 反而把「哪一行真的在响」盖住了。未播放的行静态单行省略，
                        // 颜色退回次要色，把注意力让给在播的那一行
                        enabled = isPlaying,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isPlaying) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(top = Dimens.GapTiny)
                    )
                }
            }

            // 节目单按钮：只给真的接入节目单的台，展开后再点收起
            if (hasEpg && onEpgToggle != null) {
                IconButton(
                    onClick = onEpgToggle,
                    modifier = if (isCarSurface()) Modifier.size(Dimens.TouchSecondary) else Modifier
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = if (epgPrograms != null) "收起节目单" else "节目单",
                        tint = if (epgPrograms != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Favorite Button
            IconButton(
                onClick = onFavoriteClick,
                // 车机档取次级下限 64dp 而不是 88dp：它是行内唯一的 88dp 元件，
                // 不收这一档，封面与文字压得再狠，行高也会被它钉回 88dp
                modifier = if (isCarSurface()) Modifier.size(Dimens.TouchSecondary) else Modifier
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 行内展开：当前档 + 下一档。面板自己消费点击，否则点节目单会冒泡到行、顺手起播
        if (epgPrograms != null) {
            EpgInlinePanel(programs = epgPrograms, minute = epgMinute)
        }
        }
    }
}

/**
 * 列表行内展开的节目单。
 *
 * 只给两档：用户在这里判断"现在值不值得点进去"，全天节目单由播放页的 sheet 负责。
 * 面板本身消费点击（`detectTapGestures`），不让它冒泡成"起播这一台"。
 */
@Composable
private fun EpgInlinePanel(programs: List<EpgProgram>, minute: Int) {
    val current = EpgQuery.currentAt(programs, minute)
    val next = EpgQuery.nextAfter(programs, minute)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(
                start = Dimens.CardPadding,
                end = Dimens.CardPadding,
                bottom = Dimens.GapSmall
            )
    ) {
        if (current == null && next == null) {
            Text(
                text = "今天已无节目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        if (current != null) {
            EpgInlineLine(
                label = "正在播出",
                leading = EpgQuery.formatRange(current.startMin, current.endMin),
                title = current.title,
                emphasized = true
            )
        }
        if (next != null) {
            if (current != null) Spacer(modifier = Modifier.height(Dimens.GapTiny))
            EpgInlineLine(
                label = "接下来",
                leading = EpgQuery.formatMinute(next.startMin),
                title = next.title,
                emphasized = false
            )
        }
    }
}

@Composable
private fun EpgInlineLine(
    label: String,
    leading: String,
    title: String,
    emphasized: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        if (leading.isNotBlank()) {
            Text(
                text = leading,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Dimens.GapSmall))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

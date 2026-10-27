package com.radio.chinese.ui.tag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.common.rememberNowPlaying
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

/**
 * 按标签浏览电台。两态：标签云 → 该标签下的电台列表。
 *
 * 第二态做成页内状态而不是再开一条路由：本页是从 pager 里的分类页 push 进来的，
 * 多一层返回栈就多一处「返回后页签掉回全部」的状态丢失点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    initialTag: String?,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsState()
    // 只认词表内的 key：外部路由参数不能绕过词表造出一个空标签入口
    var picked by rememberSaveable {
        mutableStateOf(initialTag?.takeIf { StationTag.isValidKey(it) })
    }
    val current = picked
    val title = current?.let { StationTag.labelOf(it) } ?: "按标签浏览"

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    // 与 PlayerScreen 同一种写法：车机档要把顶栏抬到 88dp，
                    // 否则里面的返回键只能拿到顶栏默认的 64dp 短边
                    modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                    windowInsets = NestedWindowInsets,
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (picked != null) {
                                    picked = null
                                    viewModel.selectTag(null)
                                } else {
                                    onNavigateBack()
                                }
                            },
                            // 本页车机档要求命中区≥88dp：裸 IconButton 是 48dp
                            modifier = Modifier.carTouchTarget(),
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        }
    ) { padding ->
        val modifier = if (showTopBar) Modifier.padding(padding) else Modifier
        when {
            uiState.isLoading && uiState.stations.isEmpty() ->
                LoadingState(text = "正在加载电台…", modifier = modifier)

            current == null -> TagCloud(
                modifier = modifier,
                tagsWithCount = uiState.tagsWithCount,
                onSelect = { key ->
                    picked = key
                    viewModel.selectTag(key)
                },
            )

            else -> {
                val stations = uiState.stations.filter { current in it.tags }
                if (stations.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Tag,
                        title = "这个标签下暂时没有电台",
                        hint = "返回挑一个别的标签",
                        modifier = modifier,
                    )
                } else {
                    // 与首页同一套行点击语义与波浪标记：这页以前 isPlaying 写死 false、
                    // 点击只跳播放页不起播，与其他电台列表不一致
                    val nowPlaying = rememberNowPlaying(viewModel.playerManager)
                    val onRowClick = rememberStationRowOnClick(viewModel.playerManager, onNavigateToPlayer)
                    LazyColumn(
                        modifier = modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Dimens.ScreenPadding),
                        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
                    ) {
                        items(stations, key = { it.id }) { station ->
                            StationListItem(
                                station = station,
                                isPlaying = nowPlaying.matches(station),
                                isFavorite = uiState.favoriteIds.contains(station.id),
                                onClick = { onRowClick(station) },
                                onFavoriteClick = { viewModel.toggleFavorite(station.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 标签云：按分组分区，只列真有电台的标签，chip 上带台数。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagCloud(
    tagsWithCount: List<Pair<StationTag, Int>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = tagsWithCount.toMap()
    if (counts.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Tag,
            title = "还没有可用的标签",
            hint = "电台数据里补入标签后，这里才会出现可浏览的分组",
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapLarge),
    ) {
        for ((group, tags) in StationTag.orderedByGroup()) {
            val present = tags.filter { counts.containsKey(it) }
            if (present.isEmpty()) continue
            item(key = "group-${group.name}") {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = Dimens.GapSmall),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap),
                    verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall),
                ) {
                    present.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { onSelect(tag.key) },
                            // 车机档实测过：裸 FilterChip 只有 48dp 高，32 个控件不过 88dp 线；
                            // 手机档不加约束，保持现有尺寸逐像素不变
                            modifier = Modifier.carTouchTarget(),
                            label = { Text("${tag.label} ${counts.getValue(tag)}") },
                        )
                    }
                }
            }
        }
    }
}

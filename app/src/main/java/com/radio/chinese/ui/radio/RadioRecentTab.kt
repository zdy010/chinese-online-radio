package com.radio.chinese.ui.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.rememberNowPlaying
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.Dimens

@Composable
fun RadioRecentTab(
    onNavigateToPlayer: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    searchQuery: String = ""
) {
    val stations by viewModel.recentStations.collectAsState()
    val filtered = stations.filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }

    if (filtered.isEmpty()) {
        // 搜索无结果与真的没有记录是两回事，统一说“暂无最近播放”会让用户以为记录丢了
        if (searchQuery.isNotBlank()) {
            EmptyState(
                icon = Icons.Default.Search,
                title = "最近播放里没有找到“$searchQuery”",
                hint = "换个关键字，或清空搜索框看全部记录"
            )
        } else {
            EmptyState(
                icon = Icons.Default.History,
                title = "暂无最近播放",
                hint = "在电台列表里点一个电台，听过就会出现在这里"
            )
        }
        return
    }

    val padding = PaddingValues(horizontal = Dimens.ScreenPadding)
    val gap = Arrangement.spacedBy(Dimens.ListGap)
    // 与首页同一套行点击语义：这页以前只跳播放页、不在列表里起播
    val nowPlaying = rememberNowPlaying(viewModel.playerManager)
    val onRowClick = rememberStationRowOnClick(viewModel.playerManager, onNavigateToPlayer)
    val uiState by viewModel.uiState.collectAsState()
    val cell: @Composable (RadioStation) -> Unit = { station ->
        // 与首页共用同一套行组件：以前这里手写一个只有台名的简化行，
        // 结果最近页看不到 EPG、封面、收藏和可用性评分，用户以为「EPG 没做」
        StationListItem(
            station = station,
            isPlaying = nowPlaying.matches(station),
            isFavorite = uiState.favoriteIds.contains(station.id),
            onClick = { onRowClick(station) },
            onFavoriteClick = { viewModel.toggleFavorite(station.id) },
        )
    }

    if (isCarSurface()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalArrangement = gap,
            verticalArrangement = gap
        ) {
            gridItems(filtered, key = { it.id }) { station -> cell(station) }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = gap
        ) {
            items(filtered, key = { it.id }) { station -> cell(station) }
        }
    }
}

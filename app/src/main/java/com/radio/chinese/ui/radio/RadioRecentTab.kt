package com.radio.chinese.ui.radio

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.NowPlayingWave
import com.radio.chinese.ui.common.rememberNowPlaying
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.home.HomeViewModel
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
    val cell: @Composable (RadioStation) -> Unit = { station ->
        val rowPlaying = nowPlaying.matches(station)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRowClick(station) }
                // 与 StationListItem 同一竖向节奏（12→8）：最近页与首页行高不一致会看着像两套列表
                .padding(vertical = Dimens.GapSmall)
                .heightIn(min = Dimens.RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rowPlaying) {
                NowPlayingWave(size = Dimens.IconMedium)
            } else {
                Icon(
                    Icons.Default.History,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.IconMedium)
                )
            }
            Spacer(Modifier.width(Dimens.GapMedium))
            Column(Modifier.weight(1f)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (rowPlaying) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(StationCategory.labelOf(station.category), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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

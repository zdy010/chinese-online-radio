package com.radio.chinese.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.home.HomeViewModel
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding)
    ) {
        items(filtered, key = { it.id }) { station ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToPlayer(station.id) }
                    .padding(vertical = Dimens.RowVerticalPadding)
                    .heightIn(min = Dimens.RowMinHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.History,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.IconMedium)
                )
                Spacer(Modifier.width(Dimens.GapMedium))
                Column(Modifier.weight(1f)) {
                    Text(station.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(StationCategory.labelOf(station.category), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

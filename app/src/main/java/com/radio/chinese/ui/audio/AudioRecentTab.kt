package com.radio.chinese.ui.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radio.chinese.data.entity.AudioRecentEntity
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.library.AudioLibraryViewModel
import com.radio.chinese.ui.theme.Dimens

@Composable
fun AudioRecentTab(viewModel: AudioLibraryViewModel, searchQuery: String = "") {
    val state by viewModel.uiState.collectAsState()
    val filtered = state.recentPlays.filter { searchQuery.isBlank() || it.trackName.contains(searchQuery, ignoreCase = true) }

    if (filtered.isEmpty()) {
        // 搜索无结果与真的没听过是两回事，裸灰字也看不出下一步做什么
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
                hint = "在「网络」列表里点一首唱段，听过就会出现在这里"
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
    ) {
        items(filtered, key = { it.id }) { recent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.playRecent(recent) }
                    .heightIn(min = Dimens.RowMinHeight)
                    .padding(vertical = Dimens.RowVerticalPadding),
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
                    Text(recent.trackName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(recent.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

package com.radio.chinese.ui.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.library.AudioLibraryViewModel
import com.radio.chinese.ui.theme.Dimens

@Composable
fun AudioFavoritesTab(viewModel: AudioLibraryViewModel, searchQuery: String = "") {
    val state by viewModel.uiState.collectAsState()
    val filtered = state.favorites.filter { searchQuery.isBlank() || it.trackName.contains(searchQuery, ignoreCase = true) }

    if (filtered.isEmpty()) {
        EmptyState(
            icon = Icons.Default.StarBorder,
            title = if (searchQuery.isBlank()) "还没有收藏" else "没有匹配「$searchQuery」的收藏",
            hint = if (searchQuery.isBlank()) "在「网络」列表里点星星图标，就能收藏常听的唱段" else "换个关键字试试"
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
    ) {
        items(filtered, key = { it.trackPath }) { fav ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.playFavorite(fav) }
                    .heightIn(min = Dimens.RowMinHeight)
                    .padding(vertical = Dimens.RowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconMedium))
                Spacer(Modifier.width(Dimens.GapMedium))
                Column(Modifier.weight(1f)) {
                    Text(fav.trackName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fav.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // 不再把 IconButton 压到 32dp：它紧贴整行可点区，中老年用户容易误播而不是误删
                IconButton(onClick = { viewModel.removeFavorite(fav) }) {
                    Icon(Icons.Default.Delete, "取消收藏", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Dimens.IconMedium))
                }
            }
        }
    }
}

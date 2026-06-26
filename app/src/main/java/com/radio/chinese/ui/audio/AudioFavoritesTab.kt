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
import androidx.compose.ui.unit.dp
import com.radio.chinese.ui.library.AudioLibraryViewModel

@Composable
fun AudioFavoritesTab(viewModel: AudioLibraryViewModel) {
    val state by viewModel.uiState.collectAsState()

    if (state.favorites.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无收藏", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.favorites, key = { it.trackPath }) { fav ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.playFavorite(fav) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(fav.trackName, style = MaterialTheme.typography.bodyLarge)
                        Text(fav.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 直接调用乐观更新删除
                    IconButton(onClick = { viewModel.toggleFavorite(fav.trackPath, fav.trackName, fav.sourceName) }) {
                        Icon(Icons.Default.Delete, "取消收藏", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

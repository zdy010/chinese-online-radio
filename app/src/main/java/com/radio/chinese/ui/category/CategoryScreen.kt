package com.radio.chinese.ui.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.home.StationListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onNavigateToPlayer: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            if (showTopBar) {
            TopAppBar(
                title = { Text(if (selectedCategory != null) getCategoryName(selectedCategory!!) else "分类浏览") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedCategory != null) {
                            selectedCategory = null
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            }
        }
    ) { padding ->
        if (selectedCategory == null) {
            // Category Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.categories) { (id, name) ->
                    CategoryCard(
                        categoryId = id,
                        categoryName = name,
                        stationCount = uiState.stations.count { it.category == id },
                        onClick = { selectedCategory = id }
                    )
                }
            }
        } else {
            // Stations in selected category
            val stations = uiState.stations.filter { it.category == selectedCategory }
            val currentStation by viewModel.playerManager.currentStation.collectAsState()
            val isPlaying by viewModel.playerManager.isPlaying.collectAsState()

            Column(modifier = Modifier.padding(padding)) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(stations, key = { it.id }) { station ->
                        StationListItem(
                            station = station,
                            isPlaying = currentStation?.id == station.id && isPlaying,
                            isFavorite = uiState.favoriteIds.contains(station.id),
                            onClick = {
                                viewModel.playStation(station)
                                onNavigateToPlayer(station.id)
                            },
                            onFavoriteClick = { viewModel.toggleFavorite(station.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    categoryId: String,
    categoryName: String,
    stationCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = getCategoryIcon(categoryId),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = categoryName,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$stationCount 个电台",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getCategoryIcon(categoryId: String) = when (categoryId) {
    "news" -> Icons.Default.Newspaper
    "music" -> Icons.Default.MusicNote
    "traffic" -> Icons.Default.Traffic
    "arts" -> Icons.Default.TheaterComedy
    "sports" -> Icons.Default.SportsSoccer
    "finance" -> Icons.Default.TrendingUp
    "opera" -> Icons.Default.MusicNote
    "tv_audio" -> Icons.Default.Tv
    else -> Icons.Default.Radio
}

private fun getCategoryName(categoryId: String) = when (categoryId) {
    "news" -> "新闻"
    "music" -> "音乐"
    "traffic" -> "交通"
    "arts" -> "文艺"
    "sports" -> "体育"
    "finance" -> "财经"
    "opera" -> "戏曲"
    "tv_audio" -> "电视伴音"
    else -> "综合"
}

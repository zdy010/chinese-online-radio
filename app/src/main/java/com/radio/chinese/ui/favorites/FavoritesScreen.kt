package com.radio.chinese.ui.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.home.StationListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val favoriteStations: List<RadioStation> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val stationRepository: StationRepository,
    val playerManager: PlayerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState

    init {
        observeFavorites()
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteRepository.getAllFavorites().collect { favorites ->
                val allStations = stationRepository.getAllStations()
                val favStations = favorites.mapNotNull { fav ->
                    allStations.find { it.id == fav.stationId }
                }
                _uiState.value = FavoritesUiState(
                    favoriteStations = favStations,
                    isLoading = false
                )
            }
        }
    }

    fun playStation(station: RadioStation) {
        playerManager.playStation(station)
    }

    fun removeFavorite(stationId: String) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(stationId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onNavigateToPlayer: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
    searchQuery: String = ""
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredStations = uiState.favoriteStations.filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    val currentStation by viewModel.playerManager.currentStation.collectAsState()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsState()

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                title = { Text("我的收藏") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            }
        }
    ) { padding ->
        val m = if (showTopBar) Modifier.padding(padding) else Modifier
        when {
            uiState.isLoading -> {
                LoadingState(text = "正在加载收藏…", modifier = m)
            }
            filteredStations.isEmpty() -> {
                // 搜索无结果与真的没收藏是两回事，文案不能混用
                if (searchQuery.isNotBlank()) {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "收藏里没有找到“$searchQuery”",
                        hint = "换个关键字，或清空搜索框看全部收藏",
                        modifier = m
                    )
                } else {
                    EmptyState(
                        icon = Icons.Default.Favorite,
                        title = "暂无收藏电台",
                        hint = "浏览电台列表，点击心形图标收藏",
                        modifier = m
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = m,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredStations, key = { it.id }) { station ->
                        StationListItem(
                            station = station,
                            isPlaying = currentStation?.id == station.id && isPlaying,
                            isFavorite = true,
                            onClick = {
                                viewModel.playStation(station)
                                onNavigateToPlayer(station.id)
                            },
                            onFavoriteClick = { viewModel.removeFavorite(station.id) }
                        )
                    }
                }
            }
        }
    }
}

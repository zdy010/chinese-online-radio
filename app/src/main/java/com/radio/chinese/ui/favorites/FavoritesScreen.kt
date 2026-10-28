package com.radio.chinese.ui.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.StationActionDialog
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val favoriteStations: List<RadioStation> = emptyList(),
    val customIds: Set<String> = emptySet(),
    val isLoading: Boolean = true
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val stationRepository: StationRepository,
    private val preferences: RadioPreferences,
    val playerManager: PlayerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState

    init {
        observeFavorites()
    }

    /**
     * 收藏页与其他筛选页同步（用户实测问题 4）：
     * 屏蔽的台要在这里也隐去，不能只在主页生效。
     *
     * 屏蔽标记存 DataStore，不会触发 Room 的收藏流重发；所以把收藏流与
     * invalidStationIds 流 combine 起来，屏蔽一发生收藏列表就跟着变。用 getAllStations
     * 再按 invalid 集过滤，而不是 getActiveStations：后者只拍一次快照，不会跟着流重算。
     */
    private fun observeFavorites() {
        viewModelScope.launch {
            combine(
                favoriteRepository.getAllFavorites(),
                preferences.invalidStationIds,
                preferences.customStationsJson,
            ) { favorites, invalid, _ ->
                val allStations = stationRepository.getAllStations()
                val customIds = stationRepository.getCustomStations().map { it.id }.toSet()
                Pair(
                    favorites.mapNotNull { fav -> allStations.find { it.id == fav.stationId } }
                        .filter { it.id !in invalid },
                    customIds,
                )
            }.collect { (stations, customIds) ->
                _uiState.value = FavoritesUiState(
                    favoriteStations = stations,
                    customIds = customIds,
                    isLoading = false,
                )
            }
        }
    }

    fun removeFavorite(stationId: String) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(stationId)
        }
    }

    /** 屏蔽：与其他电台列表同一个仓库操作，写 invalid 集后 combine 会重发。 */
    fun markInvalid(stationId: String) {
        viewModelScope.launch { stationRepository.markInvalid(stationId) }
    }

    fun restoreStation(stationId: String) {
        viewModelScope.launch { stationRepository.markValid(stationId) }
    }

    fun deleteCustomStation(stationId: String) {
        viewModelScope.launch {
            stationRepository.removeCustomStation(stationId)
            stationRepository.markValid(stationId)
            // 台删了又没清收藏会留一行 mapNotNull 挡掉的孤儿记录；顺手把收藏也删掉
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
    // 与首页同一套行点击语义：点非当前台只起播并留在列表
    val onRowClick = rememberStationRowOnClick(viewModel.playerManager, onNavigateToPlayer)
    // 与其他筛选页一致的屏蔽入口：长按行弹动作菜单（用户实测问题 4）
    var actionStation by remember { mutableStateOf<RadioStation?>(null) }

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
                val padding = PaddingValues(Dimens.ScreenPadding)
                val gap = Arrangement.spacedBy(Dimens.ListGap)
                val cell: @Composable (RadioStation) -> Unit = { station ->
                    StationListItem(
                        station = station,
                        isPlaying = currentStation?.id == station.id && isPlaying,
                        isFavorite = true,
                        onClick = { onRowClick(station) },
                        onFavoriteClick = { viewModel.removeFavorite(station.id) },
                        onLongClick = { actionStation = station },
                    )
                }
                if (isCarSurface()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 300.dp),
                        modifier = m,
                        contentPadding = padding,
                        horizontalArrangement = gap,
                        verticalArrangement = gap
                    ) {
                        gridItems(filteredStations, key = { it.id }) { station -> cell(station) }
                    }
                } else {
                    LazyColumn(modifier = m, contentPadding = padding, verticalArrangement = gap) {
                        items(filteredStations, key = { it.id }) { station -> cell(station) }
                    }
                }
            }
        }
    }

    actionStation?.let { station ->
        StationActionDialog(
            station = station,
            // 收藏页只列有效电台（屏蔽的已被过滤），这里“恢复为有效”分支不会命中
            isInvalid = false,
            isCustom = uiState.customIds.contains(station.id),
            onDismiss = { actionStation = null },
            onMarkInvalid = { viewModel.markInvalid(station.id); actionStation = null },
            onMarkValid = { viewModel.restoreStation(station.id); actionStation = null },
            onDelete = { viewModel.deleteCustomStation(station.id); actionStation = null },
        )
    }
}

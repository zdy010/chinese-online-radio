package com.radio.chinese.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val stations: List<RadioStation> = emptyList(),
    val filteredStations: List<RadioStation> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val categories: List<Pair<String, String>> = emptyList(),
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stationRepository: StationRepository,
    private val favoriteRepository: FavoriteRepository,
    val playerManager: PlayerManager
) : ViewModel() {

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _recentIds = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<HomeUiState> = combine(
        combine(_stations, _favoriteIds) { a, b -> Pair(a, b) },
        combine(_selectedCategory, _searchQuery) { a, b -> Pair(a, b) },
        combine(_isLoading, _error) { a, b -> Pair(a, b) }
    ) { (stations, favIds), (category, query), (loading, error) ->
        val filtered = filterStations(stations, category, query)
        HomeUiState(
            stations = stations,
            filteredStations = filtered,
            favoriteIds = favIds,
            categories = stationRepository.getCategories(),
            selectedCategory = category,
            searchQuery = query,
            isLoading = loading,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    val recentStations: StateFlow<List<RadioStation>> = combine(_recentIds, _stations) { ids, all ->
        ids.mapNotNull { id -> all.find { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadData()
        observeFavorites()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _stations.value = stationRepository.getActiveStations()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteRepository.getFavoriteIds().collect { ids ->
                _favoriteIds.value = ids.toSet()
            }
        }
    }

    fun selectCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun playStation(station: RadioStation) {
        playerManager.playStation(station)
        // 记录最近播放
        _recentIds.value = (listOf(station.id) + _recentIds.value.filter { it != station.id }).take(20)
    }

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch {
            val isFavorite = _favoriteIds.value.contains(stationId)
            favoriteRepository.toggleFavorite(stationId, isFavorite)
        }
    }

    private fun filterStations(
        stations: List<RadioStation>,
        category: String?,
        query: String
    ): List<RadioStation> {
        var result = stations
        if (category != null) {
            result = result.filter { it.category == category }
        }
        if (query.isNotBlank()) {
            val lowerQuery = query.lowercase()
            result = result.filter {
                it.name.lowercase().contains(lowerQuery) ||
                it.frequency.lowercase().contains(lowerQuery)
            }
        }
        return result
    }
}

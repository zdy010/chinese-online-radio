package com.radio.chinese.ui.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.domain.model.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IptvUiState(
    val visible: List<RadioStation> = emptyList(),
    val favorites: List<RadioStation> = emptyList(),
    val recents: List<RadioStation> = emptyList(),
    val categories: List<Pair<String, Int>> = emptyList(),
    val countries: List<Pair<String, Int>> = emptyList(),
    val query: String = "",
    val adultEnabled: Boolean = false,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
)

private data class Inputs(
    val all: List<RadioStation>,
    val query: String,
    val adult: Boolean,
    val allLanguages: Boolean,
    val loaded: Boolean,
)

/**
 * iptv 页的状态。
 *
 * 收藏与最近**用 iptv 自己的表解析**，不是复用 HomeViewModel 那套：
 * 那边是 `_stations`（只有电台）+ mapNotNull，iptv 的 id 进去会被无声丢掉。
 * 判定集中在 [IptvViewLogic]，这里是薄接线层。
 */
@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvStationRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferences: RadioPreferences,
) : ViewModel() {

    private val _all = MutableStateFlow<List<RadioStation>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _adultEnabled = MutableStateFlow(false)
    private val _allLanguages = MutableStateFlow(false)
    // 与 _all 分开：清单解析失败时也是空表，那时该显示空态而不是无限 Loading（spec §9）
    private val _loaded = MutableStateFlow(false)

    val uiState: StateFlow<IptvUiState> = combine(
        combine(_all, _query, _adultEnabled, _allLanguages, _loaded) { all, q, adult, langs, loaded ->
            Inputs(all, q, adult, langs, loaded)
        },
        favoriteRepository.getFavoriteIds(),
        preferences.radioRecentStationIds,
    ) { input, favIds, recentIds ->
        val lists = IptvViewLogic.build(
            input.all, input.query, input.adult, input.allLanguages, favIds, recentIds,
        )
        IptvUiState(
            visible = lists.visible,
            favorites = lists.favorites,
            recents = lists.recents,
            categories = lists.categories,
            countries = lists.countries,
            query = input.query,
            adultEnabled = input.adult,
            isLoading = !input.loaded,
            loadFailed = input.loaded && input.all.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IptvUiState())

    init {
        viewModelScope.launch {
            _all.value = repository.all()
            _loaded.value = true
        }
        viewModelScope.launch {
            preferences.iptvAdultEnabled.collect { _adultEnabled.value = it }
        }
        viewModelScope.launch {
            preferences.iptvAllLanguages.collect { _allLanguages.value = it }
        }
    }

    fun updateQuery(q: String) { _query.value = q }

    /** 供切台基准使用：注册的是「当前可见」集合，不是全表（V11） */
    fun currentVisible(): List<RadioStation> = uiState.value.visible

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch {
            val isFav = favoriteRepository.getFavoriteIds().first().contains(stationId)
            favoriteRepository.toggleFavorite(stationId, isFav)
        }
    }

    fun setAdultEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setIptvAdultEnabled(enabled) }
    }

    fun setAllLanguages(enabled: Boolean) {
        viewModelScope.launch { preferences.setIptvAllLanguages(enabled) }
    }
}

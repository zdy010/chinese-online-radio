package com.radio.chinese.ui.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * iptv 播放页唯一需要 ViewModel 的那件事：收藏。
 *
 * 播放状态一律读 PlayerManager（与 OperaPlayerScreen 同构），这里不复制第二份播放状态；
 * 收藏要跨页面即时同步（列表页的心形、播放页顶栏），所以订阅仓库的 Flow，
 * 不用 `getFavoriteIds().first()` 每次现查 —— 那会在连点时读到上一次的值、把收藏反成取消。
 */
@HiltViewModel
class IptvPlayerViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    init {
        viewModelScope.launch {
            favoriteRepository.getFavoriteIds().collect { _favoriteIds.value = it.toSet() }
        }
    }

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch {
            favoriteRepository.toggleFavorite(stationId, stationId in _favoriteIds.value)
        }
    }
}

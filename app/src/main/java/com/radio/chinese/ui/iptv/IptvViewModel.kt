package com.radio.chinese.ui.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.data.repository.PlayabilityStore
import com.radio.chinese.domain.model.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IptvUiState(
    val visible: List<RadioStation> = emptyList(),
    val favorites: List<RadioStation> = emptyList(),
    val recents: List<RadioStation> = emptyList(),
    val categories: List<Pair<String, Int>> = emptyList(),
    val countries: List<Pair<String, Int>> = emptyList(),
    val regions: List<Pair<String, Int>> = emptyList(),
    /** 语言 key → 频道数，按 IptvLanguage.OPTIONS 顺序，供分类页多选 */
    val languages: List<Pair<String, Int>> = emptyList(),
    val query: String = "",
    val vpnOnlyEnabled: Boolean = false,
    /** 当前选中的语言 key；空集 = 不做语言多选过滤 */
    val selectedLanguages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
)

private data class Base(
    val all: List<RadioStation>,
    val query: String,
    val loaded: Boolean,
)

private data class Filters(
    val allLanguages: Boolean,
    val vpnOnly: Boolean,
    val languages: Set<String>,
)

/**
 * iptv 页的状态。
 *
 * 收藏与最近**用 iptv 自己的表解析**，不是复用 HomeViewModel 那套：
 * 那边是 `_stations`（只有电台）+ mapNotNull，iptv 的 id 进去会被无声丢掉。
 * 判定集中在 [IptvViewLogic]，这里是薄接线层。
 *
 * 成人开关已从设置页移除：不再从偏好读 adultEnabled，build 固定传 false。
 * 数据层的 IptvAdult 判定保留 —— 上游将来真出现成人台时仍然默认隐藏。
 */
@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvStationRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferences: RadioPreferences,
    private val playabilityStore: PlayabilityStore,
) : ViewModel() {

    private val _all = MutableStateFlow<List<RadioStation>>(emptyList())
    private val _query = MutableStateFlow("")

    /**
     * 搜索框必须绑这份**同步**值：uiState 在 Default 线程重算，
     * 绑 uiState.query 会在每次按键后把输入回滚成上一帧的旧值（实测 "CCTV" 只剩 "V"）。
     */
    val query: StateFlow<String> = _query
    private val _allLanguages = MutableStateFlow(false)
    private val _vpnOnlyEnabled = MutableStateFlow(false)
    private val _selectedLanguages = MutableStateFlow<Set<String>>(emptySet())
    // 与 _all 分开：清单解析失败时也是空表，那时该显示空态而不是无限 Loading（spec §9）
    private val _loaded = MutableStateFlow(false)
    /** 收藏集合单一来源：连点两次时用 Flow 的最新值判断，不每次现查（会读到旧值、把收藏反成取消） */
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())

    // combine 最多接 5 个 flow，这里按职责先压成 4 路，避免以后再加一个过滤条件就编译不过
    val uiState: StateFlow<IptvUiState> = combine(
        combine(_all, _query, _loaded) { all, q, loaded -> Base(all, q, loaded) },
        combine(_allLanguages, _vpnOnlyEnabled, _selectedLanguages) { a, b, c ->
            Filters(a, b, c)
        },
        combine(_favoriteIds, preferences.radioRecentStationIds) { f, r -> f to r },
        // revision 只用来触发重算：分数变了，隐藏阈值可能把某些台剔掉
        combine(preferences.playabilityHideThreshold, playabilityStore.revision) { t, _ -> t },
    ) { base, filters, favAndRecent, hideThreshold ->
        val (favIds, recentIds) = favAndRecent
        val lists = IptvViewLogic.build(
            all = base.all,
            query = base.query,
            adultEnabled = false,
            allLanguagesEnabled = filters.allLanguages,
            favoriteIds = favIds.toList(),
            recentIds = recentIds,
            hideThreshold = hideThreshold,
            scoreOf = { id -> playabilityStore.scoreOf(id) },
            vpnOnlyEnabled = filters.vpnOnly,
            selectedLanguages = filters.languages,
        )
        IptvUiState(
            visible = lists.visible,
            favorites = lists.favorites,
            recents = lists.recents,
            categories = lists.categories,
            countries = lists.countries,
            regions = lists.regions,
            languages = lists.languages,
            query = base.query,
            vpnOnlyEnabled = filters.vpnOnly,
            selectedLanguages = filters.languages,
            isLoading = !base.loaded,
            loadFailed = base.loaded && base.all.isEmpty(),
        )
    // 全表过滤（12k × 7 字段）不能压在主线程：每次按键都在 Main 上重算会掉帧
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IptvUiState())

    init {
        viewModelScope.launch {
            _all.value = repository.all()
            _loaded.value = true
        }
        viewModelScope.launch {
            preferences.iptvAllLanguages.collect { _allLanguages.value = it }
        }
        viewModelScope.launch {
            preferences.iptvVpnOnlyEnabled.collect { _vpnOnlyEnabled.value = it }
        }
        viewModelScope.launch {
            preferences.iptvLanguageFilter.collect { _selectedLanguages.value = it }
        }
        viewModelScope.launch {
            favoriteRepository.getFavoriteIds().collect { _favoriteIds.value = it.toSet() }
        }
    }

    fun updateQuery(q: String) { _query.value = q }

    /** 供切台基准使用：注册的是「当前可见」集合，不是全表（V11） */
    fun currentVisible(): List<RadioStation> = uiState.value.visible

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch {
            favoriteRepository.toggleFavorite(stationId, stationId in _favoriteIds.value)
        }
    }

    fun setAllLanguages(enabled: Boolean) {
        viewModelScope.launch { preferences.setIptvAllLanguages(enabled) }
    }

    /** 分类页语言多选：空集 = 清空过滤，回到默认的「只中英」口径 */
    fun setLanguages(codes: Set<String>) {
        viewModelScope.launch { preferences.setIptvLanguageFilter(codes) }
    }

    fun toggleLanguage(code: String) {
        val next = _selectedLanguages.value.toMutableSet()
        if (!next.add(code)) next.remove(code)
        // 先乐观更新本地状态再写偏好：对话框不关闭，用户会连点多个 chip；
        // DataStore 写盘 → collect 回来有延迟，第二次点击若仍读旧集合，
        // 会把第一次的选择覆盖掉。先落本地保证连点按最新集合累加，
        // collect 回来是相同值，不产生额外刷新。
        _selectedLanguages.value = next
        viewModelScope.launch { preferences.setIptvLanguageFilter(next) }
    }
}

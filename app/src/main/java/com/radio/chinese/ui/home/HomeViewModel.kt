package com.radio.chinese.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.RegionRepository
import com.radio.chinese.data.repository.EpgRepository
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.model.EpgProgram
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.RegionBucket
import com.radio.chinese.domain.model.StationFilter
import com.radio.chinese.domain.model.StationRegion
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.domain.model.manualRegionAfterProvinceChange
import com.radio.chinese.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val stations: List<RadioStation> = emptyList(),
    val filteredStations: List<RadioStation> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val customIds: Set<String> = emptySet(),
    val categories: List<Pair<String, String>> = emptyList(),
    val tagsWithCount: List<Pair<StationTag, Int>> = emptyList(),
    val selectedCategory: String? = null,
    val selectedTag: String? = null,
    val searchQuery: String = "",
    /** 节目搜索结果，只在「全部」页分组展示；地区页只做电台过滤，不读这一项 */
    val programResults: List<EpgProgram> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stationRepository: StationRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferences: RadioPreferences,
    private val regionRepository: RegionRepository,
    private val epgRepository: EpgRepository,
    val playerManager: PlayerManager
) : ViewModel() {

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    private val _customIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _selectedTag = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _programResults = MutableStateFlow<List<EpgProgram>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _recentIds = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<HomeUiState> = combine(
        combine(_stations, _favoriteIds, _customIds) { s, f, c -> Triple(s, f, c) },
        combine(_selectedCategory, _searchQuery, _selectedTag) { c, q, t -> Triple(c, q, t) },
        combine(_isLoading, _error) { a, b -> Pair(a, b) },
        _programResults
    ) { (stations, favIds, custIds), filterState, (loading, error), programResults ->
        val (category, query, tag) = filterState
        val filtered = StationFilter.apply(stations, category, query, tag)
        // 分类条/分类网格只列“真的有电台”的分类：全量枚举会把一个都不存在的
        // 分类（如未加载全球源时的世界/成人）也摆上去，点进去只能看到一个空列表。
        // 标签同一个道理，由 StationFilter.tagsWithCount 保证。
        val usedCategories = stationRepository.getCategories().filter { (id, _) ->
            stations.any { it.category == id }
        }
        HomeUiState(
            stations = stations,
            filteredStations = filtered,
            favoriteIds = favIds,
            customIds = custIds,
            categories = usedCategories,
            tagsWithCount = StationFilter.tagsWithCount(stations),
            selectedCategory = category,
            selectedTag = tag,
            searchQuery = query,
            programResults = programResults,
            isLoading = loading,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    val recentStations: StateFlow<List<RadioStation>> = combine(_recentIds, _stations) { ids, all ->
        ids.mapNotNull { id -> all.find { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前地区（手动 > 定位缓存 > 未设置）。地区页的「本地」组与改选入口都读它。 */
    val userRegion: StateFlow<Region> = regionRepository.currentRegion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Region.NONE)

    /**
     * 地区页的分组结果。放在 ViewModel 算而不是页面里算：451 个台对一张 34 省 341 市的词表
     * 做文本命中是一次十几毫秒的活，左右翻页重建 composable 时不该反复付这笔钱。
     */
    val regionBuckets: StateFlow<List<RegionBucket>> =
        combine(_stations, regionRepository.currentRegion) { stations, region ->
            stations to region.province
        }
            .map { (stations, province) ->
                StationRegion.bucketize(stations, stationRepository.gazetteer, province)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 省市词表里的省份，供地区页的手动选择对话框用。
     * 与设置页读的是同一份 region_gazetteer.json，不另维护省份清单。
     */
    val provinceNames: List<String> get() = stationRepository.gazetteer.provinceNames

    /**
     * 地区页改选「本地」省份：写的是与设置页同一份手动地区偏好，首页排序会立即跟着变。
     *
     * 只改省、原省不变时保留已选的城市：设置页写的是「省|市」两维，
     * `StationSorter.tierOf` 的同市档要看 user.city。这里若固定写「省|」，
     * 用户在地区页弹窗里点一下当前省（弹窗就是用它高亮的，很容易被当成「确认」），
     * 设置页选过的城市就被抹掉，首页排序从同市退化成同省。
     */
    fun setManualRegion(province: String) {
        val stored = manualRegionAfterProvinceChange(province, userRegion.value)
        viewModelScope.launch { preferences.setManualRegion(stored) }
    }

    init {
        loadData()
        observeFavorites()
        observeRecentStations()
        observeRegionChanges()
    }

    /**
     * 地区一变就重拉电台列表：getActiveStations 的本地优先序依赖当前地区，
     * 以前只在建仓时读一次，设置页定位/改选手动地区后要重启 App 才生效。
     * drop(1) 跳过初始发射（init 已 loadData），distinctUntilChanged 避免重复重拉。
     */
    private fun observeRegionChanges() {
        viewModelScope.launch {
            regionRepository.currentRegion
                .distinctUntilChanged()
                .drop(1)
                .collect { loadData() }
        }
    }

    /** 失败态的「重新加载」入口，供列表错误页调用。 */
    fun retry() {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _stations.value = stationRepository.getActiveStations()
                _customIds.value = stationRepository.getCustomStations().map { it.id }.toSet()
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

    /** 最近播过的电台从 DataStore 读，不再只存活于内存（重启后仍能看到）。 */
    private fun observeRecentStations() {
        viewModelScope.launch {
            preferences.radioRecentStationIds.collect { ids ->
                _recentIds.value = ids
            }
        }
    }

    fun selectCategory(category: String?) {
        _selectedCategory.value = category
    }

    /** 标签筛选入口；传 null 回到不设标签筛选。与分类、搜索互不干扰。 */
    fun selectTag(tag: String?) {
        _selectedTag.value = tag?.takeIf { StationTag.isValidKey(it) }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        searchPrograms(query)
    }

    private var programSearchJob: Job? = null

    /**
     * 节目搜索只搜本地已缓存的当天节目。
     *
     * 全量 72 个台实时搜会在用户每敲一个字时打出几十个请求，远超「单台单日按需」的边界；
     * 缓存由播放页和节目单页顺手填满，搜不到的台就是还没看过的那几个。
     */
    private fun searchPrograms(query: String) {
        programSearchJob?.cancel()
        if (query.isBlank()) {
            _programResults.value = emptyList()
            return
        }
        programSearchJob = viewModelScope.launch {
            val cached = epgRepository.cachedPrograms(LocalDate.now(EpgQuery.BROADCAST_ZONE).toString())
            _programResults.value = EpgQuery.search(cached, query).take(MAX_PROGRAM_RESULTS)
        }
    }

    fun playStation(station: RadioStation) {
        // 最近播放由 PlayerManager 在真正确认起播成功后写入 DataStore，
        // 此处不再维护第二份内存列表，避免两个写入源互相覆盖。
        playerManager.playStation(station)
    }

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch {
            val isFavorite = _favoriteIds.value.contains(stationId)
            favoriteRepository.toggleFavorite(stationId, isFavorite)
        }
    }

    /**
     * 主页长按电台即可“随时编辑”：与节目源管理页共用同一套仓库操作。
     * 主页只列有效电台，标无效后重拉列表会自动把它隐去；自定义源还能删除。
     */
    fun markStationInvalid(stationId: String) {
        viewModelScope.launch {
            stationRepository.markInvalid(stationId)
            loadData()
        }
    }

    fun restoreStation(stationId: String) {
        viewModelScope.launch {
            stationRepository.markValid(stationId)
            loadData()
        }
    }

    fun deleteCustomStation(stationId: String) {
        viewModelScope.launch {
            stationRepository.removeCustomStation(stationId)
            stationRepository.markValid(stationId)
            loadData()
        }
    }

    private companion object {
        /** 再多的节目结果也会把电台列表挤出首屏；20 行足够翻到目标 */
        const val MAX_PROGRAM_RESULTS = 20
    }
}

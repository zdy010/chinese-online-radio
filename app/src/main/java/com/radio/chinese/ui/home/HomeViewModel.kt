package com.radio.chinese.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.RegionRepository
import com.radio.chinese.data.repository.EpgRepository
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.epg.nowPlayingTitles
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
import kotlinx.coroutines.delay
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
import java.time.LocalDateTime
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

    /**
     * 列表里那行「正在播出」的标题：stationId → 此刻在播节目名。
     * 只由本地当天缓存算出，未接入或此刻在空档的台不出现在这里。
     */
    private val _epgNowTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val epgNowTitles: StateFlow<Map<String, String>> = _epgNowTitles

    /** 接入节目单的台 id 集合，列表据此给台名挂标记。与时间无关，直接来自映射表。 */
    val epgStationIds: Set<String> get() = epgRepository.stationIds

    // 当天缓存快照：批量补齐后刷新它，每分钟只据它重算标题，不重复打接口
    private var epgProgramsByStation: Map<String, List<EpgProgram>> = emptyMap()
    // 快照对应的北京日期：跨过零点后它仍是昨天，据此判断要不要重拉当天
    private var epgSnapshotDate: String? = null
    private var epgTicker: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        combine(_stations, _favoriteIds, _customIds) { s, f, c -> Triple(s, f, c) },
        combine(_selectedCategory, _searchQuery, _selectedTag) { c, q, t -> Triple(c, q, t) },
        combine(_isLoading, _error) { a, b -> Pair(a, b) },
        _programResults
    ) { (stations, favIds, custIds), filterState, (loading, error), programResults ->
        val (category, query, tag) = filterState
        val filtered = StationFilter.apply(stations, category, query, tag)
        // 分类条/分类网格只列“真的有电台”的分类：全量枚举会把一个都不存在的
        // 分类（如未加载全球源时的世界）也摆上去，点进去只能看到一个空列表。
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
        observeInvalidChanges()
        startEpgTicker()
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

    /**
     * 屏蔽集（标记无效）一变就重拉列表：列表是 getActiveStations 的一次性快照，
     * 只在本 VM 自己 markStationInvalid 时刷新。但屏蔽入口现在也在收藏页（另一个 VM）,
     * 它只写 DataStore、不会叫到这里 loadData——不订阅的话，从收藏页屏蔽后台，
     * 同一 pager 里翻回主页/分类/地区/标签仍看得到它，直到重启（用户实测问题 4 的反方向）。
     * drop(1) 跳过初始发射（init 已 loadData），distinctUntilChanged 避免重复重拉。
     */
    private fun observeInvalidChanges() {
        viewModelScope.launch {
            preferences.invalidStationIds
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
                // 台库到手后顺手补齐一次节目单缓存：首次进列表就能看到「正在播出」，
                // 之后每分钟只重算标题，不再打接口（loadAll 内部命中 6 小时缓存）
                refreshEpgCache()
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

    /**
     * 批量补齐接入台的当天节目缓存。与节目单页共用同一份缓存与限流：
     * loadAll 内部逐台走 [EpgRepository.load]，命中 6 小时 TTL 就不重拉。
     * 拉完刷新快照并立即重算一次标题，不等下一分钟的 tick。
     */
    private fun refreshEpgCache() {
        val ids = epgRepository.stationIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val date = LocalDate.now(EpgQuery.BROADCAST_ZONE).toString()
            epgRepository.loadAll(ids, date)
            epgProgramsByStation = epgRepository.cachedPrograms(date).groupBy { it.stationId }
            epgSnapshotDate = date
            recomputeEpgTitles()
        }
    }

    /**
     * 每分钟跟一次当前档。节目档按分钟切换，但列表顺序不动——
     * 与 [com.radio.chinese.ui.epg.EpgViewModel] 同一个取舍：每分钟抖行序会让下滑的手跟丢。
     * 同一天内只做纯内存重算不发请求；只有跨过北京零点（快照日期落后于今天）才重拉一次当天缓存。
     */
    private fun startEpgTicker() {
        if (epgTicker?.isActive == true) return
        epgTicker = viewModelScope.launch {
            while (true) {
                delay(60_000L)
                val today = LocalDate.now(EpgQuery.BROADCAST_ZONE).toString()
                // 跨过北京零点：快照还是昨天的排播，纯重算只会把今天的节目名筛成空，
                // 必须重拉当天缓存；同一天内则只重算，不打接口。
                if (today != epgSnapshotDate) refreshEpgCache() else recomputeEpgTitles()
            }
        }
    }

    private fun recomputeEpgTitles() {
        val date = LocalDate.now(EpgQuery.BROADCAST_ZONE).toString()
        val minute = EpgQuery.minuteOf(LocalDateTime.now(EpgQuery.BROADCAST_ZONE))
        _epgNowTitles.value = nowPlayingTitles(epgProgramsByStation, date, minute, epgRepository.stationIds)
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

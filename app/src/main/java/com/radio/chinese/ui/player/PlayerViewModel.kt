package com.radio.chinese.ui.player

import androidx.media3.common.Player
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.EpgRepository
import com.radio.chinese.data.repository.EpgSnapshot
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.timer.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val station: RadioStation? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val error: String? = null,
    val status: String? = null,
    val isFavorite: Boolean = false,
    val allStations: List<RadioStation> = emptyList(),
    val currentSource: StationSource? = null,
    val sourceScores: List<Pair<StationSource, Float>> = emptyList()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val stationRepository: StationRepository,
    private val favoriteRepository: FavoriteRepository,
    private val epgRepository: EpgRepository,
    val playerManager: PlayerManager,
    val sleepTimer: SleepTimer
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    /**
     * 当前台的节目单。null 表示这个台没有接入、或一次都没拉到过——UI 整块不渲染。
     *
     * 不放进 [PlayerUiState]：那个 state 由播放状态的 combine 重建，节目单与播放无关，
     * 混进去会逼着每处重建都去回填一份，逻辑反而更脏。
     */
    private val _epg = MutableStateFlow<EpgSnapshot?>(null)
    val epg: StateFlow<EpgSnapshot?> = _epg

    private var epgStationId: String? = null
    private var epgToken = 0

    /** 切台用的电台列表只需一份；用活动列表，标记无效的电台不能被切到 */
    private var allStations: List<RadioStation> = emptyList()

    /** 收藏 Flow 的收集器，同一个 station 只保留一个，否则长期停留会线性累积 */
    private var favoriteJob: Job? = null

    init {
        viewModelScope.launch {
            allStations = stationRepository.getActiveStations()
        }
        viewModelScope.launch {
            combine(
                playerManager.currentStation,
                combine(
                    playerManager.isPlaying,
                    playerManager.playbackState,
                    playerManager.error,
                    playerManager.status
                ) { isPlaying, state, err, status -> Quartet(isPlaying, state, err, status) },
                combine(
                    playerManager.currentSource,
                    playerManager.sourceScores
                ) { source, scores -> Pair(source, scores) }
            ) { station, playInfo, sourceInfo ->
                val (isPlaying, state, err, status) = playInfo
                val (source, scores) = sourceInfo
                PlayerUiState(
                    station = station,
                    isPlaying = isPlaying,
                    playbackState = state,
                    error = err,
                    status = status,
                    // 重建状态时回填收藏标记，否则心形图标会先闪灭再由收集器补回
                    isFavorite = _uiState.value.isFavorite,
                    currentSource = source,
                    sourceScores = scores,
                    allStations = allStations
                )
            }.collect { state ->
                _uiState.value = state
                state.station?.let { observeFavorite(it.id) }
            }
        }
    }

    private data class Quartet<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun observeFavorite(stationId: String) {
        if (stationId == observingFavoriteId) return
        observingFavoriteId = stationId
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            favoriteRepository.isFavorite(stationId).collect { isFav ->
                _uiState.value = _uiState.value.copy(isFavorite = isFav)
            }
        }
    }

    private var observingFavoriteId: String? = null

    fun loadStation(stationId: String) {
        viewModelScope.launch {
            // 列表项点击已经起播，这里再起播一次会让直播流握手时延翻倍；
            // 同一个电台直接返回，只负责展示。
            if (playerManager.currentStation.value?.id == stationId) return@launch
            val station = stationRepository.getStationById(stationId)
            if (station != null) {
                playerManager.playStation(station)
            }
        }
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    /**
     * 取某台某天的节目单。页面每过一分钟、以及跨天时各调一次，重复调不会重复请求：
     * 缓存 6 小时内直接用，失败后 30 秒内节流，都关在 [EpgRepository] 里。
     *
     * 不取消上一次的协程：它可能正在写库，取消会把「删了旧行、没插新行」的中间态留给下一分钟。
     * 用自增 token 只合并结果——只有最后一次调用的结果能落到 UI。
     */
    fun loadEpg(stationId: String, date: String) {
        if (epgStationId != stationId) {
            // 换了台先清空，否则新台加载完成前会短暂显示上一个台的节目
            epgStationId = stationId
            _epg.value = null
        }
        val token = ++epgToken
        viewModelScope.launch {
            val snapshot = epgRepository.load(stationId, date)
            if (token == epgToken) _epg.value = snapshot
        }
    }

    fun toggleFavorite() {
        val station = _uiState.value.station ?: return
        viewModelScope.launch {
            favoriteRepository.toggleFavorite(station.id, _uiState.value.isFavorite)
        }
    }

    fun switchToSource(sourceUrl: String) {
        playerManager.switchToSource(sourceUrl)
    }

    fun playNext() {
        playerManager.playNext(_uiState.value.allStations)
    }

    fun playPrevious() {
        playerManager.playPrevious(_uiState.value.allStations)
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimer.startTimer(minutes)
    }

    fun cancelSleepTimer() {
        sleepTimer.cancelTimer()
    }
}

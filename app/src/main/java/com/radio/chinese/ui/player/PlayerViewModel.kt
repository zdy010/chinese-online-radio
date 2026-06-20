package com.radio.chinese.ui.player

import androidx.media3.common.Player
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val station: RadioStation? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val error: String? = null,
    val isFavorite: Boolean = false,
    val allStations: List<RadioStation> = emptyList(),
    val currentSource: StationSource? = null,
    val sourceScores: List<Pair<StationSource, Float>> = emptyList()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val stationRepository: StationRepository,
    private val favoriteRepository: FavoriteRepository,
    val playerManager: PlayerManager,
    val sleepTimer: SleepTimer
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                playerManager.currentStation,
                combine(
                    playerManager.isPlaying,
                    playerManager.playbackState,
                    playerManager.error
                ) { isPlaying, state, err -> Triple(isPlaying, state, err) },
                combine(
                    playerManager.currentSource,
                    playerManager.sourceScores
                ) { source, scores -> Pair(source, scores) }
            ) { station, playInfo, sourceInfo ->
                val (isPlaying, state, err) = playInfo
                val (source, scores) = sourceInfo
                PlayerUiState(
                    station = station,
                    isPlaying = isPlaying,
                    playbackState = state,
                    error = err,
                    currentSource = source,
                    sourceScores = scores,
                    allStations = stationRepository.getAllStations()
                )
            }.collect { state ->
                _uiState.value = state
                state.station?.let { checkFavorite(it.id) }
            }
        }
    }

    private fun checkFavorite(stationId: String) {
        viewModelScope.launch {
            favoriteRepository.isFavorite(stationId).collect { isFav ->
                _uiState.value = _uiState.value.copy(isFavorite = isFav)
            }
        }
    }

    fun loadStation(stationId: String) {
        viewModelScope.launch {
            val station = stationRepository.getStationById(stationId)
            if (station != null) {
                playerManager.playStation(station)
            }
        }
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
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

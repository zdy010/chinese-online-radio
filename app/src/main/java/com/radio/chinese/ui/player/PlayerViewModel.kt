package com.radio.chinese.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.timer.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val station: RadioStation? = null,
    val isPlaying: Boolean = false,
    val isFavorite: Boolean = false,
    val allStations: List<RadioStation> = emptyList()
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
        observePlayer()
    }

    private fun observePlayer() {
        viewModelScope.launch {
            playerManager.currentStation.collect { station ->
                _uiState.value = _uiState.value.copy(
                    station = station,
                    allStations = stationRepository.getAllStations()
                )
                station?.let { checkFavorite(it.id) }
            }
        }
        viewModelScope.launch {
            playerManager.isPlaying.collect { playing ->
                _uiState.value = _uiState.value.copy(isPlaying = playing)
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
            if (station != null && _uiState.value.station?.id != stationId) {
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

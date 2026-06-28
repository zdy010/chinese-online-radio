package com.radio.chinese.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.BuildConfig
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.StreamCheckResult
import com.radio.chinese.service.StreamChecker
import com.radio.chinese.service.StreamStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManageUiState(
    val stations: List<RadioStation> = emptyList(),
    val invalidIds: Set<String> = emptySet(),
    val customIds: Set<String> = emptySet(),
    val checkResults: Map<String, StreamCheckResult> = emptyMap(),
    val isChecking: Boolean = false,
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val easterEggMessage: String? = null
)

@HiltViewModel
class ManageViewModel @Inject constructor(
    private val repository: StationRepository,
    private val streamChecker: StreamChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManageUiState())
    val uiState: StateFlow<ManageUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val stations = repository.getAllStations()
            val invalidIds = repository.getInvalidIds()
            val customStations = repository.getCustomStations()
            _uiState.update {
                it.copy(
                    stations = stations,
                    invalidIds = invalidIds,
                    customIds = customStations.map { s -> s.id }.toSet(),
                    isLoading = false
                )
            }
        }
    }

    fun checkAllStations() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val stations = _uiState.value.stations
            _uiState.update { it.copy(isChecking = true, checkedCount = 0, totalCount = stations.size) }

            val results = stations.map { station ->
                async {
                    streamChecker.checkStream(station.primaryUrl, station.id)
                }
            }.awaitAll()

            val resultMap = results.associateBy { it.stationId }
            _uiState.update {
                it.copy(
                    isChecking = false,
                    checkResults = resultMap,
                    checkedCount = stations.size
                )
            }
        }
    }

    fun checkSingleStation(station: RadioStation) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    checkResults = it.checkResults + (station.id to StreamCheckResult(
                        station.id, StreamStatus.CHECKING
                    ))
                )
            }
            val result = streamChecker.checkStream(station.primaryUrl, station.id)
            _uiState.update {
                it.copy(checkResults = it.checkResults + (station.id to result))
            }
        }
    }

    fun markInvalid(stationId: String) {
        viewModelScope.launch {
            repository.markInvalid(stationId)
            _uiState.update { it.copy(invalidIds = it.invalidIds + stationId) }
        }
    }

    fun markValid(stationId: String) {
        viewModelScope.launch {
            repository.markValid(stationId)
            _uiState.update { it.copy(invalidIds = it.invalidIds - stationId) }
        }
    }

    fun addCustomStation(
        name: String,
        streamUrl: String,
        category: String,
        frequency: String,
        description: String
    ) {
        // 检测彩蛋
        if (BuildConfig.ENABLE_EASTER_EGG && isEasterEgg(streamUrl)) {
            viewModelScope.launch {
                repository.loadDefaultStationsToCustom()
                loadData()
                _uiState.update { it.copy(easterEggMessage = "🎉 默认电台已加载！可在列表中查看和管理") }
            }
            return
        }

        viewModelScope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            val station = RadioStation(
                id = id,
                name = name,
                category = category,
                streamUrl = streamUrl,
                frequency = frequency,
                description = description
            )
            repository.addCustomStation(station)
            loadData()
        }
    }

    /** 检测是否为彩蛋触发码 */
    private fun isEasterEgg(streamUrl: String): Boolean {
        val trimmed = streamUrl.trim()
        return trimmed == com.radio.chinese.config.EasterEggConfig.RADIO_EASTER_EGG || trimmed == "xxx"
    }

    fun removeCustomStation(stationId: String) {
        viewModelScope.launch {
            repository.removeCustomStation(stationId)
            repository.markValid(stationId)
            loadData()
        }
    }

    fun clearEasterEggMessage() {
        _uiState.update { it.copy(easterEggMessage = null) }
    }
}
